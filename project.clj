(defproject scrappy "0.1.0-SNAPSHOT"
  :description "A collection of scrapers for interesting sites."
  :url "http://vedang.me/techlog/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.apache.logging.log4j/log4j-core "2.8"]
                 [org.apache.logging.log4j/log4j-slf4j-impl "2.8"]
                 [com.fasterxml.jackson.core/jackson-core "2.8.5"]
                 [com.fasterxml.jackson.core/jackson-databind "2.8.5"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.8.5"]
                 [com.climate/claypoole "1.1.4"]
                 [io.aleph/dirigiste "0.1.5"]
                 [clj-http "3.6.1"]
                 [org.jsoup/jsoup "1.10.3"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
