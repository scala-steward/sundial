#!/usr/bin/env bash

# Note: First time this script is executed will fail as, after the db is initialised it will shutdown.
# You will have to execute:
# > docker rm sundial_web_1
# and then restart this script
# If an authentication error is thrown at db startup time, you will have to delete also the db container:
# > docker rm sundial_db_1

VERSION=`git describe --tags --dirty --always | sed "s/^v//"`
sbt docker:publishLocal
docker tag sundial:$VERSION sundial:latest
docker-compose up
