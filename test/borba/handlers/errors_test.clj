(ns borba.handlers.errors-test
  (:require
   [borba.handlers.errors :as errors]
   [borba.handlers.json :as json]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as jsonista]))

(set! *warn-on-reflection* true)

(defn- pedestal-wrapped
  "Wraps an exception the way Pedestal does when an interceptor throws it: a
   message about the interceptor, and the exception itself in the data."
  [thrown]
  (ex-info (str (.getName (class thrown)) " in Interceptor :x/y - "
                (ex-message thrown))
           (merge (ex-data thrown)
                  {:execution-id   1
                   :stage          :enter
                   :interceptor    :x/y
                   :exception-type (keyword (.getName (class thrown)))
                   :exception      thrown})))

(defn- read-body
  [response]
  (jsonista/read-value (:body response) jsonista/keyword-keys-object-mapper))

(deftest describe-authored-error-test
  (testing "a client error says what its author said, with its own code"
    (let [thrown (ex-info "Token expired" {:status 401 :error :token-expired})]
      (is (= {:status 401 :code :token-expired :message "Token expired"}
             (errors/describe thrown)))))

  (testing "a client error without a code takes the code of its status"
    (is (= :unauthorized
           (:code (errors/describe (ex-info "no" {:status 401}))))))

  (testing "a status without a code of its own is a bad request, or an
            internal error from 500"
    (is (= :bad-request
           (:code (errors/describe (ex-info "teapot" {:status 418})))))
    (is (= :internal-error
           (:code (errors/describe (ex-info "odd" {:status 599}))))))

  (testing "a code that is not a keyword is not trusted"
    (is (= :forbidden
           (:code (errors/describe
                   (ex-info "no" {:status 403 :error "anything"}))))))

  (testing "details meant for the client are passed on"
    (is (= [{:field :name}]
           (:details (errors/describe
                      (ex-info "bad"
                               {:status  422
                                :details [{:field :name}]})))))))

(deftest describe-server-error-test
  (testing "a server error hides its message and keeps its cause for the log"
    (let [thrown (ex-info "connection to db:5432 refused"
                          {:status 503 :details [:secret]})
          result (errors/describe thrown)]
      (is (= 503 (:status result)))
      (is (= :service-unavailable (:code result)))
      (is (= "An internal error occurred." (:message result)))
      (is (identical? thrown (:cause result)))
      (is (not (contains? result :details)))))

  (testing "an exception without a status is an internal error"
    (let [thrown (IllegalStateException. "password=hunter2")
          result (errors/describe thrown)]
      (is (= {:status  500
              :code    :internal-error
              :message "An internal error occurred."}
             (dissoc result :cause)))
      (is (identical? thrown (:cause result)))))

  (testing "a status that is not an error status is not one"
    (is (= 500 (:status (errors/describe (ex-info "x" {:status 200})))))
    (is (= 500 (:status (errors/describe (ex-info "x" {:status "404"})))))))

(deftest describe-wrapped-exception-test
  (testing "reads the exception Pedestal wrapped, not the wrapper"
    (let [thrown (ex-info "Token expired" {:status 401 :error :token-expired})
          result (errors/describe (pedestal-wrapped thrown))]
      (is (= "Token expired" (:message result)))
      (is (= 401 (:status result)))))

  (testing "the message of the wrapper never reaches the client"
    (let [thrown  (IllegalStateException. "password=hunter2")
          result  (errors/describe (pedestal-wrapped thrown))
          cause   (:cause result)]
      (is (= "An internal error occurred." (:message result)))
      (is (identical? thrown cause)))))

(deftest response-test
  (testing "is JSON, with the code, the message and the request id"
    (let [response (errors/response 400 :invalid-json "Bad." "abc" nil)]
      (is (= 400 (:status response)))
      (is (= json/content-type (get-in response [:headers "Content-Type"])))
      (is (= {:error      "invalid-json"
              :message    "Bad."
              :request-id "abc"}
             (read-body response)))))

  (testing "leaves out the request id and the details when there are none"
    (is (= {:error "x" :message "y"}
           (read-body (errors/response 400 :x "y" nil nil)))))

  (testing "has the details when there are some"
    (is (= [{:field "name"}]
           (:details (read-body
                      (errors/response 422
                                       :validation-failed
                                       "Invalid."
                                       "abc"
                                       [{:field "name"}])))))))
