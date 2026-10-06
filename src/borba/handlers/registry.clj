(ns borba.handlers.registry
  "The registry of the handlers of a service, one `defmethod` per handler,
   which `:service/handlers` turns into chains of interceptors.

   A handler is registered under the keyword that routes refer to it by. The
   method receives the components of the service, to close over what the
   handler needs, and returns the function that handles the requests:

     (defmethod registry/handler :user/create
       [_ {:keys [db]}]
       (fn [{:keys [body-params]}]
         {:status 201 :body (users/create! db body-params)}))

   The function receives one map and returns an HTTP response map, whose :body
   is written as JSON when it is a map or a collection:

     :components     the components of the service
     :body-params    the JSON body, as data with keyword keys; {} without a body
     :query-params   the query string, as a map with keyword keys
     :path-params    the parameters of the path
     :header-params  the headers, with lower-case string keys
     :request-id     the id of the request, also in the logs and the response
     :request        the Ring request, for what the rest does not cover

   To run more interceptors for one handler, after the ones every handler has,
   list their keywords. They are the ones registered with
   borba.interceptors.registry, and they are built into the map that
   `:service/handlers` takes as :interceptors:

     (defmethod registry/handler-interceptors :admin/dashboard
       [_]
       [:auth/admin :audit/log-access])")

(defmulti handler
  "Builds the function that handles the requests of a handler key.
   - dispatch-key: the keyword that routes refer to the handler by
   - components: the components of the service, as `:service/handlers` was
     given them"
  (fn [dispatch-key _components] dispatch-key))

(defmethod handler :default
  [dispatch-key _components]
  (throw (ex-info (str "no handler is registered for " dispatch-key)
                  {:error       ::no-handler
                   :handler-key dispatch-key})))

(defmulti handler-interceptors
  "Returns the keywords of the interceptors to run for a handler, after the
   ones every handler has. There are none unless a method says so.
   - dispatch-key: the keyword that routes refer to the handler by"
  (fn [dispatch-key] dispatch-key))

(defmethod handler-interceptors :default
  [_dispatch-key]
  [])
