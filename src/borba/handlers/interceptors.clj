(ns borba.handlers.interceptors
  "The interceptors that every handler runs, in the order they run:

     request-id          gives the request an id, and puts it in the response
     access-log          logs the method, the path, the status and the time
     http-error-handler  turns an exception into a response that leaks nothing
     inject-components   puts the components of the service in the request
     parse-query         reads the query string into :query-params
     parse-path-params   makes sure :path-params is there
     parse-headers       copies :headers into :headers-map
     parse-body          reads a JSON body into :body-params, within a limit
     json-response       writes a map or collection body as JSON on the way out

   The request-id and the error handler come first so that everything after
   them, and every failure, has an id and a response of the same shape."
  (:require
   [borba.handlers.errors :as errors]
   [borba.handlers.json :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [io.pedestal.http.route :as route])
  (:import
   (com.fasterxml.jackson.core JsonProcessingException)
   (java.io InputStream)))

(set! *warn-on-reflection* true)

(def default-max-body-bytes
  "The largest body a request may have, 1 MiB, unless told otherwise."
  (* 1024 1024))

(def ^:private status-bad-request 400)
(def ^:private status-payload-too-large 413)
(def ^:private status-unsupported-media-type 415)

(def ^:private max-request-id-length 128)
(def ^:private request-id-header "x-request-id")
(def ^:private response-request-id-header "X-Request-Id")
(def ^:private content-length-header "content-length")
(def ^:private content-type-header "content-type")
(def ^:private nanos-per-millisecond 1000000)
(def ^:private json-media-type "application/json")
(def ^:private json-media-suffix "+json")

(def ^:private valid-request-id
  (re-pattern (str "[A-Za-z0-9._-]{1," max-request-id-length "}")))

(def ^:private empty-body-params {})

;; Request id and access log

(def request-id
  "Gives each request an id, in :request-id, and puts it in the X-Request-Id
   header of the response. The id is the one the client sent in X-Request-Id
   when it is safe to write to a log (letters, digits, dot, underscore and
   hyphen, up to 128 characters), and a new UUID otherwise."
  {:name  ::request-id
   :enter (fn [ctx]
            (let [supplied (get-in ctx [:request :headers request-id-header])
                  id       (if (and supplied
                                    (re-matches valid-request-id supplied))
                             supplied
                             (str (random-uuid)))]
              (assoc-in ctx [:request :request-id] id)))
   :leave (fn [ctx]
            (if (:response ctx)
              (assoc-in ctx
                        [:response :headers response-request-id-header]
                        (get-in ctx [:request :request-id]))
              ctx))})

(def access-log
  "Logs each request when it is done: the method, the path (never the query
   string, which can carry secrets), the status, the time in milliseconds and
   the request id."
  {:name  ::access-log
   :enter (fn [ctx]
            (assoc ctx ::started-at (System/nanoTime)))
   :leave (fn [ctx]
            (let [request    (:request ctx)
                  elapsed-ms (quot (- (System/nanoTime) (::started-at ctx))
                                   nanos-per-millisecond)]
              (log/infof "%s %s %s %d ms request-id=%s"
                         (str/upper-case (name (:request-method request)))
                         (:uri request)
                         (or (get-in ctx [:response :status]) "-")
                         elapsed-ms
                         (:request-id request))
              ctx))})

;; Failures

(def http-error-handler
  "Turns any exception of the chain into a response with the typed body of
   borba.handlers.errors. A failure of the server is logged with its cause and
   the request id; none of it reaches the client."
  {:name  ::http-error-handler
   :error (fn [ctx ex]
            (let [{:keys [status code message details cause]}
                  (errors/describe ex)

                  id (get-in ctx [:request :request-id])]
              (when cause
                (log/error cause (str "request " id " failed")))
              (assoc ctx
                     :response
                     (errors/response status code message id details))))})

;; The request

(defn inject-components
  "Returns an interceptor that puts the components of the service in the
   request, as :components.
   - components: the components of the service"
  [components]
  {:name  ::inject-components
   :enter (fn [ctx]
            (assoc-in ctx [:request :components] components))})

(defn- with-flags
  "A parameter without a value, as in ?flag, comes from Pedestal under a nil
   key, which JSON cannot write. It is a parameter with an empty value."
  [params]
  (if-let [flags (get params nil)]
    (reduce (fn [acc flag] (assoc acc (keyword flag) ""))
            (dissoc params nil)
            (if (string? flags) [flags] flags))
    params))

(defn- query-params
  [query-string]
  (try
    (with-flags (route/parse-query-string query-string))
    (catch IllegalArgumentException _
      (throw (ex-info "The query string is not valid."
                      {:status status-bad-request
                       :error  :invalid-query})))))

(def parse-query
  "Reads the query string into :query-params, a map with keyword keys and
   string values: empty when there is no query string, a vector of strings for
   a parameter that is repeated, and an empty string for one without a value.
   A query string that cannot be decoded is refused with 400."
  {:name  ::parse-query
   :enter (fn [ctx]
            (assoc-in ctx
                      [:request :query-params]
                      (query-params (get-in ctx [:request :query-string]))))})

(def parse-path-params
  "Makes sure :path-params is in the request, empty when the route has none."
  {:name  ::parse-path-params
   :enter (fn [ctx]
            (update-in ctx [:request :path-params] #(or % {})))})

(def parse-headers
  "Copies :headers into :headers-map, with lower-case string keys."
  {:name  ::parse-headers
   :enter (fn [ctx]
            (assoc-in ctx
                      [:request :headers-map]
                      (get-in ctx [:request :headers] {})))})

(defn- declared-length
  "Returns the length the request declares for its body, or nil."
  [request]
  (when-let [declared (get-in request [:headers content-length-header])]
    (parse-long declared)))

(defn- json-media-type?
  [request]
  (let [media-type (some-> (get-in request [:headers content-type-header])
                           (str/split #";" 2)
                           first
                           str/trim
                           str/lower-case)]
    (boolean (and media-type
                  (or (= json-media-type media-type)
                      (str/ends-with? media-type json-media-suffix))))))

(defn- too-large
  [max-body-bytes]
  (ex-info (str "The request body is larger than " max-body-bytes " bytes.")
           {:status status-payload-too-large
            :error  :payload-too-large}))

(defn- body-bytes
  "Reads the body of a request, at most one byte past the limit so that a body
   over it is known without reading it all."
  ^bytes [request max-body-bytes]
  (if-let [^InputStream body (:body request)]
    (.readNBytes body (int (inc max-body-bytes)))
    (byte-array 0)))

(defn- read-body
  [request max-body-bytes]
  (let [declared (declared-length request)]
    (when (and declared (> declared max-body-bytes))
      (throw (too-large max-body-bytes)))
    (let [body (body-bytes request max-body-bytes)]
      (cond
        (> (alength body) max-body-bytes)
        (throw (too-large max-body-bytes))

        (zero? (alength body))
        empty-body-params

        (not (json-media-type? request))
        (throw (ex-info "The request body must be application/json."
                        {:status status-unsupported-media-type
                         :error  :unsupported-media-type}))

        :else
        (try
          (let [value (json/read-json body)]
            (if (nil? value) empty-body-params value))
          (catch JsonProcessingException _
            (throw (ex-info "The request body is not valid JSON."
                            {:status status-bad-request
                             :error  :invalid-json}))))))))

(defn body-parser
  "Returns an interceptor that reads the body of a request into :body-params.
   The body must be JSON, read strictly (see borba.handlers.json), and no
   larger than the limit, which is checked against what the request declares
   and against what it sends. A request without a body, or with a JSON null,
   has an empty map. A body that is too large is refused with 413, one that is
   not JSON with 415, and one that is not valid JSON with 400.
   - max-body-bytes: the largest body in bytes (default 1 MiB)"
  [{:keys [max-body-bytes]
    :or   {max-body-bytes default-max-body-bytes}}]
  {:name  ::parse-body
   :enter (fn [ctx]
            (assoc-in ctx
                      [:request :body-params]
                      (read-body (:request ctx) max-body-bytes)))})

(def parse-body
  "The body parser with the default limit."
  (body-parser {}))

;; The response

(def json-response
  "Writes the body of the response as JSON, with the JSON content type, when it
   is a map or a collection. A string body is left as it is."
  {:name  ::json-response
   :leave (fn [ctx]
            (let [body (get-in ctx [:response :body])]
              (if (coll? body)
                (-> ctx
                    (assoc-in [:response :body] (json/write-json body))
                    (assoc-in [:response :headers "Content-Type"]
                              json/content-type))
                ctx)))})
