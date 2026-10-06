(ns borba.handlers.errors
  "How a failure becomes the response to an HTTP request.

   The body is a map with the code of the error, a message and the id of the
   request:

     {:error :invalid-json, :message \"...\", :request-id \"...\"}

   The code is the contract, as in borba.railway: it is what a client matches
   on, so it is stable and specific.

   An exception becomes a response in one of two ways. One that carries an HTTP
   :status in its data is an error its author meant for the client, such as
   (ex-info \"Token expired\" {:status 401 :error :token-expired}): the client
   gets that status, the code in :error (or the code of the status) and, when
   it is a client error, its message. Anything else is a bug or a broken
   dependency, and the client gets a 500 whose message says nothing about it.
   What went wrong goes to the log, never to the response."
  (:require
   [borba.handlers.json :as json]))

(def ^:private first-error-status 400)
(def ^:private first-server-error-status 500)
(def ^:private last-error-status 599)
(def ^:private internal-error-status 500)

(def ^:private internal-error-message "An internal error occurred.")

(def status->code
  "The code of the errors that carry a status and no code of their own."
  {400 :bad-request
   401 :unauthorized
   403 :forbidden
   404 :not-found
   405 :method-not-allowed
   409 :conflict
   413 :payload-too-large
   415 :unsupported-media-type
   422 :validation-failed
   429 :too-many-requests
   500 :internal-error
   502 :bad-gateway
   503 :service-unavailable
   504 :gateway-timeout})

(defn- original
  "Returns the exception an interceptor threw. Pedestal wraps it in another one
   whose message names the interceptor and the class, which is for the log."
  [ex]
  (or (:exception (ex-data ex)) ex))

(defn- error-status?
  [status]
  (and (int? status)
       (<= first-error-status status last-error-status)))

(defn- code-of
  [data status]
  (let [code (:error data)]
    (cond
      (keyword? code)
      code

      (contains? status->code status)
      (status->code status)

      (>= status first-server-error-status)
      :internal-error

      :else
      :bad-request)))

(defn describe
  "Returns what to tell the client about an exception, as a map of :status,
   :code and :message, and :details when the error carries some that are meant
   for the client. When the failure is the server's it also has :cause, the
   exception to log, and the message is one that says nothing about it.
   - ex: the exception an interceptor threw, as Pedestal hands it to :error"
  [ex]
  (let [thrown (original ex)
        data   (ex-data thrown)
        status (:status data)]
    (if (error-status? status)
      (let [server-error? (>= status first-server-error-status)]
        (cond-> {:status  status
                 :code    (code-of data status)
                 :message (if server-error?
                            internal-error-message
                            (ex-message thrown))}
          (and (not server-error?) (some? (:details data)))
          (assoc :details (:details data))

          server-error?
          (assoc :cause thrown)))
      {:status  internal-error-status
       :code    :internal-error
       :message internal-error-message
       :cause   thrown})))

(defn response
  "Returns the response to an error: its status, the JSON content type and the
   body as JSON text.
   - status: the HTTP status
   - code: the keyword that names the error
   - message: what to tell the client
   - request-id: the id of the request, or nil
   - details: data about the error that is safe to show, or nil"
  [status
   code
   message
   request-id
   details]
  {:status  status
   :headers {"Content-Type" json/content-type}
   :body    (json/write-json
             (cond-> {:error code :message message}
               request-id
               (assoc :request-id request-id)

               (some? details)
               (assoc :details details)))})
