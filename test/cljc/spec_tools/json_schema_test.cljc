(ns spec-tools.json-schema-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec :as s]
            [spec-tools.core :as st]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
    #?(:clj
            [scjsv.core :as scjsv])
            [spec-tools.json-schema :as jsc]))

(s/def ::integer integer?)
(s/def ::string string?)
(s/def ::set #{1 2 3})

(deftest simple-spec-test
  (testing "primitive predicates"
    ;; You're intented to call jsc/to-json with a registered spec, but to avoid
    ;; boilerplate, we do inline specization here.
    (is (= (jsc/to-json (s/spec int?)) {:type "integer" :format "int64"}))
    (is (= (jsc/to-json (s/spec integer?)) {:type "integer"}))
    (is (= (jsc/to-json (s/spec float?)) {:type "number"}))
    (is (= (jsc/to-json (s/spec double?)) {:type "number"}))
    (is (= (jsc/to-json (s/spec string?)) {:type "string"}))
    (is (= (jsc/to-json (s/spec boolean?)) {:type "boolean"}))
    (is (= (jsc/to-json (s/spec nil?)) {:type "null"}))
    (is (= (jsc/to-json #{1 2 3}) {:enum [1 3 2]})))
  (testing "clojure.spec predicates"
    (is (= (jsc/to-json (s/nilable ::string)) {:oneOf [{:type "string"} {:type "null"}]}))
    (is (= (jsc/to-json (s/int-in 1 10)) {:allOf [{:type "integer" :format "int64"} {:minimum 1 :maximum 10}]})))
  (testing "simple specs"
    (is (= (jsc/to-json ::integer) {:type "integer"}))
    (is (= (jsc/to-json ::set) {:enum [1 3 2]})))

  (testing "clojure.specs"
    (is (= (jsc/to-json (s/keys :req-un [::integer] :opt-un [::string]))
           {:type "object"
            :properties {"integer" {:type "integer"} "string" {:type "string"}}
            :required ["integer"]}))
    (is (= (jsc/to-json (s/or :int integer? :string string?))
           {:anyOf [{:type "integer"} {:type "string"}]}))
    (is (= (jsc/to-json (s/and integer? pos?))
           {:allOf [{:type "integer"} {:minimum 0 :exclusiveMinimum true}]}))
    ;; merge
    (is (= (jsc/to-json (s/every integer?)) {:type "array" :items {:type "integer"}}))
    (is (= (jsc/to-json (s/every-kv string? integer?))
           {:type "object" :additionalProperties {:type "integer"}}))
    (is (= (jsc/to-json (s/coll-of string?)) {:type "array" :items {:type "string"}}))
    (is (= (jsc/to-json (s/coll-of string? :into '())) {:type "array" :items {:type "string"}}))
    (is (= (jsc/to-json (s/coll-of string? :into [])) {:type "array" :items {:type "string"}}))
    (is (= (jsc/to-json (s/coll-of string? :into #{})) {:type "array" :items {:type "string"}, :uniqueItems true}))
    (is (= (jsc/to-json (s/map-of string? integer?))
           {:type "object" :additionalProperties {:type "integer"}}))
    (is (= (jsc/to-json (s/* integer?)) {:type "array" :items {:type "integer"}}))
    (is (= (jsc/to-json (s/+ integer?)) {:type "array" :items {:type "integer"} :minItems 1}))
    (is (= (jsc/to-json (s/? integer?)) {:type "array" :items {:type "integer"} :minItems 0}))
    (is (= (jsc/to-json (s/alt :int integer? :string string?))
           {:anyOf [{:type "integer"} {:type "string"}]}))
    (is (= (jsc/to-json (s/cat :int integer? :string string?))
           {:type "array"
            :minItems 2
            :maxItems 2
            :items {:anyOf [{:type "integer"} {:type "string"}]}}))
    ;; & is broken (http://dev.clojure.org/jira/browse/CLJ-2152)
    (is (= (jsc/to-json (s/tuple integer? string?))
           {:type "array" :items [{:type "integer"} {:type "string"}] :minItems 2}))
    ;; keys* is broken (http://dev.clojure.org/jira/browse/CLJ-2152)
    (is (= (jsc/to-json (s/map-of string? clojure.core/integer?))
           {:type "object" :additionalProperties {:type "integer"}}))
    (is (= (jsc/to-json (s/nilable string?))
           {:oneOf [{:type "string"} {:type "null"}]})))
  (testing "failing clojure.specs"
    (is (not= (jsc/to-json (s/coll-of (s/tuple string? any?) :into {}))
              {:type "object", :additionalProperties {:type "string"}}))))

#?(:clj
   (defn test-spec-conversion [spec]
     (let [validate (scjsv/validator (jsc/to-json spec))]
       (testing (str "with spec " spec)
         (checking "JSON schema accepts the data generated by the spec gen" 100
           [x (s/gen spec)]
           (is (nil? (validate x)) (str x " (" spec ") does not conform to JSON Schema")))))))

(s/def ::compound (s/keys :req-un [::integer] :opt-un [::string]))

#?(:clj
   (deftest validating-test
     (test-spec-conversion ::integer)
     (test-spec-conversion ::string)
     (test-spec-conversion ::set)
     (test-spec-conversion ::compound)
     (test-spec-conversion (s/nilable ::string))
     (test-spec-conversion (s/int-in 0 100))))

;; Test the example from README

(s/def ::age (s/and integer? #(> % 18)))

(def person-spec
  (st/data-spec
    ::person
    {::id integer?
     :age ::age
     :name string?
     :likes {string? boolean?}
     (st/req :languages) #{keyword?}
     (st/opt :address) {:street string?
                        :zip string?}}))

(deftest readme-test
  (is (= {:type "object"
          :required ["id" "age" "name" "likes" "languages"]
          :properties
          {"id" {:type "integer"}
           "age" {:type "integer"}
           "name" {:type "string"}
           "likes" {:type "object" :additionalProperties {:type "boolean"}}
           "languages" {:type "array", :items {:type "string"}, :uniqueItems true}
           "address" {:type "object"
                      :required ["street" "zip"]
                      :properties {"street" {:type "string"}
                                   "zip" {:type "string"}}}}}
         (jsc/to-json person-spec))))

(deftest additional-json-schema-data-test
  (is (= {:type "integer"
          :title "integer"
          :description "it's an int"
          :default 42}
         (jsc/to-json
           (st/spec
             {:spec integer?
              :name "integer"
              :description "it's an int"
              :json-schema/default 42})))))

(deftest deeply-nested-test
  (is (= {:type "array"
          :items {:type "array"
                  :items {:type "array"
                          :items {:type "array"
                                  :items {:type "string"}}}}}
         (jsc/to-json
           (st/data-spec
             ::nested
             [[[[string?]]]])))))
