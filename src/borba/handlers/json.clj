(ns borba.handlers.json
  "The JSON of the HTTP layer. It is strict when it reads: by default Jackson
   ignores whatever follows the first value of a document and keeps the last of
   two equal keys of an object, which is how two parsers come to read one
   request in two ways. Here both are errors. Keys are read as keywords, and
   the nesting is limited by Jackson to 1000 levels.

   It writes what jsonista writes: keywords as their names, instants and dates
   as ISO-8601 strings."
  (:require
   [jsonista.core :as jsonista])
  (:import
   (com.fasterxml.jackson.core JsonParser$Feature)
   (com.fasterxml.jackson.databind DeserializationFeature ObjectMapper)))

(set! *warn-on-reflection* true)

(def content-type
  "The media type of what this namespace writes."
  "application/json; charset=utf-8")

(def ^:private ^ObjectMapper strict-mapper
  (doto ^ObjectMapper (jsonista/object-mapper {:decode-key-fn true})
    (.enable DeserializationFeature/FAIL_ON_TRAILING_TOKENS)
    (.configure JsonParser$Feature/STRICT_DUPLICATE_DETECTION true)))

(defn read-json
  "Reads one JSON value, and returns it with its keys as keywords. Throws a
   Jackson JsonProcessingException when the text is not valid JSON, has
   something after the value, or has an object with a key twice.
   - source: the JSON as a string or as bytes"
  [source]
  (jsonista/read-value source strict-mapper))

(defn write-json
  "Returns the JSON text of a value.
   - value: the data to write, with keywords, strings, numbers, booleans, nil,
     maps and collections"
  [value]
  (jsonista/write-value-as-string value))
