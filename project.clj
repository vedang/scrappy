(defproject scrappy "0.1.0-SNAPSHOT"
  :description "A collection of scrapers for interesting sites."
  :url "http://vedang.me/techlog/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
