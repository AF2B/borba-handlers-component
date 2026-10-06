(ns borba.handlers.component
  "The Integrant component that builds the chain of every registered handler:

     :service/handlers
     {:components     #ig/ref :service/components
      :interceptors   #ig/ref :service/interceptors
      :max-body-bytes 1048576}

   Its value is a map from the handler key to its chain, a map of :chain, the
   interceptors that run before the handler, and :handler, the interceptor that
   calls it. `:http/routes` puts the interceptors of a route between the two.

   The chain is the one of borba.handlers.interceptors, then the interceptors
   the handler asks for with borba.handlers.registry/handler-interceptors,
   which are looked up in :interceptors, the map that `:service/interceptors`
   builds. A handler that is not a function, or that asks for an interceptor
   that is not there, fails the start naming it.

   The key :borba/not-found is the handler of a request that no route matches.
   It is built here, with a short chain that does not read a body, and it is
   reserved.

   The handlers are found by the `defmethod`s that have been loaded, so the
   namespaces that register them go under `:service/namespaces`."
  (:require
   [borba.handlers.interceptors :as interceptors]
   [borba.handlers.registry :as registry]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(def not-found-key
  "The key of the handler of a request that no route matches."
  :borba/not-found)

(def ^:private status-not-found 404)

(defn- callable?
  [candidate]
  (or (fn? candidate) (var? candidate)))

(defn- request-for
  "Returns what a handler receives, from the context of the chain."
  [request]
  {:components    (:components request)
   :body-params   (:body-params request {})
   :query-params  (:query-params request {})
   :path-params   (:path-params request {})
   :header-params (:headers-map request {})
   :request-id    (:request-id request)
   :request       request})

(defn- checked-response
  "Returns the response of a handler, or throws when it is not one: a map with
   an integer :status. Without this a handler that returns nil, or a vector,
   leaves the client waiting or with a 404."
  [handler-key response]
  (if (and (map? response) (int? (:status response)))
    response
    (throw (ex-info (str "the handler " handler-key
                         " did not return a response map with a :status")
                    {:error       ::invalid-response
                     :handler-key handler-key}))))

(defn- handler->interceptor
  "Wraps the function of a handler in the interceptor that calls it."
  [handler-key
   handler-fn]
  {:name  handler-key
   :enter (fn [ctx]
            (let [response (handler-fn (request-for (:request ctx)))]
              (assoc ctx :response (checked-response handler-key response))))})

(defn- base-chain
  "Returns the interceptors every handler runs first."
  [components
   max-body-bytes]
  [interceptors/request-id
   interceptors/access-log
   interceptors/http-error-handler
   (interceptors/inject-components components)
   interceptors/parse-query
   interceptors/parse-path-params
   interceptors/parse-headers
   (interceptors/body-parser {:max-body-bytes max-body-bytes})
   interceptors/json-response])

(defn- asked-for
  "Looks up the interceptors a handler asks for, and fails naming the first one
   that is not there."
  [handler-key
   interceptor-keys
   available]
  (mapv (fn [interceptor-key]
          (or (get available interceptor-key)
              (throw (ex-info
                      (str "the handler " handler-key
                           " asks for the interceptor " interceptor-key
                           ", which is not registered; is :interceptors"
                           " configured?")
                      {:error       ::unknown-interceptor
                       :handler-key handler-key
                       :interceptor interceptor-key}))))
        interceptor-keys))

(defn- build-handler
  "Builds the chain and the handler interceptor of one handler key."
  [{:keys [components available base]}
   handler-key]
  (let [handler-fn (registry/handler handler-key components)
        asked      (registry/handler-interceptors handler-key)]
    (when-not (callable? handler-fn)
      (throw (ex-info (str "the handler " handler-key " is not a function")
                      {:error       ::invalid-handler
                       :handler-key handler-key})))
    (when-not (sequential? asked)
      (throw (ex-info (str "handler-interceptors of " handler-key
                           " must return a vector of keywords")
                      {:error       ::invalid-handler-interceptors
                       :handler-key handler-key})))
    {:chain   (into base (asked-for handler-key asked available))
     :handler (handler->interceptor handler-key handler-fn)}))

(defn- not-found-chain
  "Builds the handler of a request that no route matches. It has no body to
   read and no interceptors to ask for."
  []
  {:chain   [interceptors/request-id
             interceptors/access-log
             interceptors/http-error-handler
             interceptors/json-response]
   :handler {:name  not-found-key
             :enter (fn [_ctx]
                      (throw (ex-info "No route matches the request."
                                      {:status status-not-found
                                       :error  :not-found})))}})

(defn build
  "Builds the chain of every registered handler, and returns them in a map from
   the handler key to its :chain and :handler, with the reserved
   :borba/not-found among them.
   - components: the components of the service, handed to each handler
   - interceptors: the map that `:service/interceptors` builds, where the
     interceptors a handler asks for are looked up
   - max-body-bytes: the largest request body in bytes (default 1 MiB)"
  [{:keys [components interceptors max-body-bytes]
    :or   {max-body-bytes interceptors/default-max-body-bytes}}]
  (when-not (and (int? max-body-bytes)
                 (pos? max-body-bytes)
                 (< max-body-bytes Integer/MAX_VALUE))
    (throw (ex-info ":max-body-bytes must be a positive integer"
                    {:error          ::invalid-max-body-bytes
                     :max-body-bytes max-body-bytes})))
  (let [registered (dissoc (methods registry/handler) :default)
        context    {:components components
                    :available  (or interceptors {})
                    :base       (base-chain components max-body-bytes)}]
    (when (contains? registered not-found-key)
      (throw (ex-info (str "the handler key " not-found-key " is reserved")
                      {:error       ::reserved-handler-key
                       :handler-key not-found-key})))
    (assoc (into {}
                 (map (fn [handler-key]
                        [handler-key (build-handler context handler-key)]))
                 (keys registered))
           not-found-key
           (not-found-chain))))

(defmethod ig/init-key :service/handlers
  [_ options]
  (let [handlers (build options)
        names    (sort (map str (remove #{not-found-key} (keys handlers))))]
    (if (seq names)
      (log/infof "registered %d handler(s): %s"
                 (count names)
                 (str/join ", " names))
      (log/warn "no handlers are registered; are the namespaces that register"
                "them listed under :service/namespaces?"))
    handlers))
