(defproject link "0.5.5"
  :description "A clojure framework for nonblocking network programming"
  :url "http://github.com/sunng87/link"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [io.netty/netty-all "4.0.17.Final"]]
  :scm {:name "git"
        :url "http://github.com/sunng87/link"}
  :global-vars {*warn-on-reflection* true})
