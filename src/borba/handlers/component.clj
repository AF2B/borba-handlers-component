(ns borba.handlers.component
  "Integrant component for :service/handlers.

   Automatically discovers all handlers registered via
   borba.handlers.registry/handler defmulti and builds their
   Pedestal interceptor chains.

   Chain structure (per handler):
     [error-handler
      inject-components
      parse-body
      parse-query
      parse-path-params
      parse-headers
      json-response
      ...custom interceptors from handler-interceptors defmulti...
      <handler interceptor>]

   The handler interceptor wraps the handler function and normalises
   the request into a flat map before calling it:
     {:keys [components body-params query-params path-params header-params]}

   The handler function must return an HTTP response map:
     {:status 200 :body {...}}"
  (:require [integrant.core :as ig]
            [borba.handlers.registry :as registry]
            [borba.handlers.interceptors :as i]))

;; ── Internal: wrap handler fn in a Pedestal interceptor ─────────────────────

(defn- handler->interceptor
  "Wraps a handler function in a Pedestal :enter interceptor.
   The handler fn receives a flat request map and must return a response map."
  [handler-key handler-fn]
  {:name  handler-key
   :enter (fn [ctx]
            (let [req      (:request ctx)
                  response (handler-fn
                            {:components    (:components req)
                             :body-params   (:body-params req {})
                             :query-params  (:query-params req {})
                             :path-params   (:path-params req {})
                             :header-params (:headers-map req {})})]
              (assoc ctx :response response)))})

;; ── Internal: build full chain for one handler ──────────────────────────────

(defn- build-chain
  "Builds the full interceptor chain for handler-key."
  [handler-key components inject]
  (let [handler-fn     (registry/handler handler-key components)
        extra-keys     (registry/handler-interceptors handler-key)
        custom-inters  (mapv #(registry/interceptor % components) extra-keys)
        base-chain     [i/http-error-handler
                        inject
                        i/parse-body
                        i/parse-query
                        i/parse-path-params
                        i/parse-headers
                        i/json-response]
        handler-inter  (handler->interceptor handler-key handler-fn)]
    (-> base-chain
        (into custom-inters)
        (conj handler-inter))))

;; ── Integrant lifecycle ──────────────────────────────────────────────────────

(defmethod ig/init-key :service/handlers
  [_ {:keys [components]}]
  (let [inject       (i/inject-components components)
        all-handlers (dissoc (methods registry/handler) :default)]
    (when (empty? all-handlers)
      (println "⚠️  [handlers] No handlers registered. Did you require your routes namespace?"))
    (reduce
     (fn [acc handler-key]
       (assoc acc handler-key (build-chain handler-key components inject)))
     {}
     (keys all-handlers))))
