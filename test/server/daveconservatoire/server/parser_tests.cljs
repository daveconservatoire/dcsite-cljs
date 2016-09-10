(ns daveconservatoire.server.parser-tests
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer [is are run-tests async testing deftest do-report]]
            [cljs.core.async :refer [<!]]
            [common.async :refer [<?]]
            [pathom.core :as pc]
            [pathom.sql :as ps]
            [daveconservatoire.server.parser :as p]
            [daveconservatoire.server.test-shared :as ts]
            [com.rpl.specter :as sk :refer [setval select selected? view transform ALL FIRST LAST END filterer comp-paths keypath]]
            [om.next :as om]))

(deftest parse-read-not-found
  (async done
    (go
      (is (= (<! (p/parse {} [:invalid]))
             {:invalid [:error :not-found]}))
      (done))))

(deftest parser-read-courses
  (async done
    (go
      (try
        (is (= (<? (p/parse {::ps/db ts/connection} [:app/courses]))
               {:app/courses [{:db/id 4 :db/table :course} {:db/id 7 :db/table :course}]}))

        (is (= (<! (p/parse {::ps/db ts/connection} [{:app/courses [:db/id :course/title]}]))
               {:app/courses [{:db/id 4 :course/title "Reading Music" :db/table :course}
                              {:db/id 7 :course/title "Music:  A Beginner's Guide" :db/table :course}]}))

        (is (= (<! (p/parse {::ps/db ts/connection} [{:app/courses [:db/id :course/title]}]))
               {:app/courses [{:db/id 4 :course/title "Reading Music" :db/table :course}
                              {:db/id 7 :course/title "Music:  A Beginner's Guide" :db/table :course}]}))

        (is (= (<! (p/parse {::ps/db ts/connection} [{:app/courses [:db/id :course/home-type]}]))
               {:app/courses [{:db/id 4 :course/home-type :course.type/multi-topic :db/table :course}
                              {:db/id 7 :course/home-type :course.type/multi-topic :db/table :course}]}))

        (is (= (<! (p/parse {::ps/db ts/connection} [{:app/courses {:course.type/multi-topic [:db/id :course/home-type]
                                                                :course.type/single-topic [:db/id :course/title]}}]))
               {:app/courses [{:db/id 4 :course/home-type :course.type/multi-topic :db/table :course}
                              {:db/id 7 :course/home-type :course.type/multi-topic :db/table :course}]}))
        (catch :default e
          (do-report
            {:type :error, :message (.-message e) :actual e})))
      (done))))

(deftest test-parse-read-lesson-by-slug
  (async done
    (go
      (try
        (is (= (->> (p/parse {::ps/db ts/connection} [{[:lesson/by-slug "percussion"] [:db/id :lesson/title]}]) <!)
               {[:lesson/by-slug "percussion"] {:db/id 9 :db/table :lesson :lesson/title "Percussion"}}))

        (is (= (->> (p/parse {::ps/db ts/connection} [{[:lesson/by-slug "percussion"] [:db/id :lesson/type]}]) <!)
               {[:lesson/by-slug "percussion"] {:db/id 9 :db/table :lesson :lesson/type :lesson.type/video}}))

        (is (= (->> (p/parse {::ps/db ts/connection} [{[:lesson/by-slug "invalid"] [:db/id :lesson/title]}]) <!)
               {[:lesson/by-slug "invalid"] [:error :row-not-found]}))
        (catch :default e
          (do-report
            {:type :error, :message (.-message e) :actual e})))
      (done))))

(deftest parser-read-route-data
  (async done
    (go
      (is (= (<! (p/parse {::ps/db ts/connection} [{:route/data [:app/courses]}]))
             {:route/data {:app/courses [{:db/id 4 :db/table :course} {:db/id 7 :db/table :course}]}}))
      (is (= (<! (p/parse {::ps/db ts/connection} [{:ph/anything [:app/courses]}]))
             {:ph/anything {:app/courses [{:db/id 4 :db/table :course} {:db/id 7 :db/table :course}]}}))
      (done))))

(deftest test-read-lesson-union
  (let [lesson-union {:lesson.type/video    [:lesson/type :lesson/title]
                      :lesson.type/playlist [:lesson/type :lesson/description]
                      :lesson.type/exercise [:lesson/type :lesson/title :url/slug]}]
    (async done
      (go
        (is (= (->> (p/parse {::ps/db ts/connection}
                             [{[:lesson/by-slug "percussion"]
                               lesson-union}]) <!)
               {[:lesson/by-slug "percussion"]
                {:db/id 9 :db/table :lesson :lesson/title "Percussion" :lesson/type :lesson.type/video}}))
        (is (= (->> (p/parse {::ps/db ts/connection}
                             [{[:lesson/by-slug "percussion-playlist"]
                               lesson-union}]) <!)
               {[:lesson/by-slug "percussion-playlist"]
                {:db/id 11 :db/table :lesson :lesson/description "" :lesson/type :lesson.type/playlist}}))
        (is (= (->> (p/parse {::ps/db ts/connection}
                             [{[:lesson/by-slug "tempo-markings"]
                               lesson-union}]) <!)
               {[:lesson/by-slug "tempo-markings"]
                {:db/id 67 :db/table :lesson :lesson/title "Exercise: Tempo Markings Quiz" :lesson/type :lesson.type/exercise
                 :url/slug "tempo-markings"}}))
        (done)))))

(deftest test-read-me
  (async done
    (go
      (is (= (->> (p/parse {::ps/db ts/connection :current-user-id 720} [{:app/me [:db/id]}]) <!
                  :app/me)
             {:db/id 720 :db/table :user}))
      (is (= (->> (p/parse {::ps/db ts/connection} [{:app/me [:db/id]}]) <!
                  :app/me)
             nil))
      (done))))
