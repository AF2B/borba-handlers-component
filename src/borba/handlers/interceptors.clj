(ns borba.handlers.interceptors
  "Built-in Pedestal interceptors provided by borba-handlers-component.

   These are infrastructure-level interceptors applied to every route
   automatically by the :service/handlers component:

     error-handler      — catches exceptions, returns structured JSON
     inject-components  — injects :components into request map
     parse-body         — parses JSON body → :body-params
     parse-query        — copies :params → :query-params
     parse-path-params  — copies :path-params
     parse-headers      — copies :headers → :headers-map
     json-response      — serializes :body as JSON on leave"
  (:require [cheshire.core :as json])
  (:import (java.io BufferedReader)))

;; ── Error handler ────────────────────────────────────────────────────────────
;; Named http-error-handler to avoid clash with clojure.core/error-handler
;; introduced in Clojure 1.12.

(def http-error-handler
  "Catches any exception thrown in the interceptor chain and returns a
   structured JSON error response. Place this first in every chain."
  {:name  ::http-error-handler
   :error (fn [ctx ex]
            (let [data    (ex-data ex)
                  message (.getMessage ^Exception ex)]
              (assoc ctx
                     :response {:status  (or (:status data) 500)
                                :headers {"Content-Type" "application/json; charset=utf-8"}
                                :body    (json/generate-string
                                          {:error   (or (:error data) :internal-error)
                                           :message message})})))})

;; ── Component injection ──────────────────────────────────────────────────────

(defn inject-components
  "Returns an interceptor that injects the components map into every request.
   Called once at system init with the resolved component map."
  [components]
  {:name  ::inject-components
   :enter (fn [ctx]
            (assoc-in ctx [:request :components] components))})

;; ── Request parsers ──────────────────────────────────────────────────────────

(def parse-body
  "Parses the request body as JSON and assocs it as :body-params.
   Returns an empty map when there is no body."
  {:name  ::parse-body
   :enter (fn [ctx]
            (let [body (get-in ctx [:request :body])]
              (if body
                (let [body-str (if (instance? BufferedReader body)
                                 (slurp body)
                                 (str body))
                      params   (when (seq body-str)
                                 (json/parse-string body-str true))]
                  (assoc-in ctx [:request :body-params] (or params {})))
                (assoc-in ctx [:request :body-params] {}))))})

(def parse-query
  "Copies :params into :query-params for uniform access."
  {:name  ::parse-query
   :enter (fn [ctx]
            (assoc-in ctx [:request :query-params]
                      (get-in ctx [:request :params] {})))})

(def parse-path-params
  "Ensures :path-params is present (Pedestal already sets it; this normalises it)."
  {:name  ::parse-path-params
   :enter (fn [ctx]
            (assoc-in ctx [:request :path-params]
                      (get-in ctx [:request :path-params] {})))})

(def parse-headers
  "Copies :headers into :headers-map for uniform access."
  {:name  ::parse-headers
   :enter (fn [ctx]
            (assoc-in ctx [:request :headers-map]
                      (get-in ctx [:request :headers] {})))})

;; ── JSON response serialiser ─────────────────────────────────────────────────

(def json-response
  "Serialises response :body as JSON on leave and sets Content-Type."
  {:name  ::json-response
   :leave (fn [ctx]
            (let [body (get-in ctx [:response :body])]
              (if (or (map? body) (sequential? body))
                (-> ctx
                    (assoc-in [:response :body] (json/generate-string body))
                    (assoc-in [:response :headers "Content-Type"]
                              "application/json; charset=utf-8"))
                ctx)))})
