(ns borba.handlers.registry
  "Handler and interceptor registry via defmulti.

   Handlers and interceptors are registered by keyword in the service code
   and automatically discovered by the :service/handlers Integrant component.

   ── Registering a handler ────────────────────────────────────────────────────

     (defmethod borba.handlers.registry/handler :user/create [_ _]
       user-create-handler)

   The defmethod receives [dispatch-key components] so you can optionally
   close over components for stateful handlers:

     (defmethod borba.handlers.registry/handler :user/create [_ {:keys [cache]}]
       (fn [{:keys [body-params]}]
         ;; cache is available via closure
         ...))

   ── Handler function signature ───────────────────────────────────────────────

   Each handler function receives a single flat map:

     (defn user-create-handler
       [{:keys [components body-params query-params path-params header-params]}]
       {:status 201 :body {:id ...)})

   It must return an HTTP response map {:status N :body ...}.

   ── Extra interceptors per handler ──────────────────────────────────────────

   To attach additional interceptors to a specific handler, override
   handler-interceptors:

     (defmethod borba.handlers.registry/handler-interceptors :admin/dashboard [_]
       [:auth/admin-check :audit/log-access])

   The interceptor keywords are resolved via the `interceptor` defmulti:

     (defmethod borba.handlers.registry/interceptor :auth/admin-check [_ components]
       {:name  :auth/admin-check
        :enter (fn [ctx]
                 (let [token (get-in ctx [:request :headers-map \"authorization\"])]
                   (if (valid-token? token)
                     ctx
                     (throw (ex-info \"Unauthorized\" {:status 401})))))})")

;; ── Handler registry ─────────────────────────────────────────────────────────

(defmulti handler
  "Registry for route handlers. Dispatches on handler-key keyword.

   Usage:
     (defmethod borba.handlers.registry/handler :user/create [_ _]
       user-create-handler)"
  (fn [dispatch-key _components] dispatch-key))

(defmethod handler :default [k _]
  (throw (ex-info (str "[handlers] No handler registered for: " k)
                  {:handler-key k})))

;; ── Interceptor registry ─────────────────────────────────────────────────────

(defmulti interceptor
  "Registry for custom interceptors. Dispatches on interceptor-key keyword.

   Usage:
     (defmethod borba.handlers.registry/interceptor :auth/check [_ components]
       {:name  :auth/check
        :enter (fn [ctx] ...)})"
  (fn [dispatch-key _components] dispatch-key))

(defmethod interceptor :default [k _]
  (throw (ex-info (str "[handlers] No interceptor registered for: " k)
                  {:interceptor-key k})))

;; ── Per-handler extra interceptors ───────────────────────────────────────────

(defmulti handler-interceptors
  "Returns a vector of interceptor keys to prepend to a handler's chain.
   Override per handler to attach custom interceptors.

   Default: no extra interceptors.

   Usage:
     (defmethod borba.handlers.registry/handler-interceptors :admin/dashboard [_]
       [:auth/admin-check :audit/log-access])"
  (fn [dispatch-key] dispatch-key))

(defmethod handler-interceptors :default [_]
  [])
