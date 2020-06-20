clojure -A:jar loomviz.jar
mvn deploy:deploy-file -Dfile=loomviz.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
