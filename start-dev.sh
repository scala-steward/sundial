eval $(docker-machine env dev)
sbt clean compile stage && docker-compose build && docker-compose up
