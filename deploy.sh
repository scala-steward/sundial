#!/bin/sh

#Change these to match your environment
REGION=
DOCKER_REGISTRY=
DOCKER_REPO=svc-sundial
# An s3 bucket for deployment assets
CODEDEPLOY_S3_BUCKET=

sbt clean stage

#Only needed if you're using EC2 Container Registry
$(aws ecr get-login --region $REGION)

BUILD_ID=$(git describe --tags --dirty --always | sed "s/^v//")

docker build -t $DOCKER_REGISTRY/$DOCKER_REPO:$BUILD_ID .

docker push $DOCKER_REGISTRY/$DOCKER_REPO:$BUILD_ID

m4 -D_BUILD_ID_=$BUILD_ID -D_DOCKER_REPO_=$DOCKER_REPO -D_DOCKER_REGISTRY_=$DOCKER_REGISTRY codedeploy/start.template > codedeploy/start.sh
m4 -D_BUILD_ID_=$BUILD_ID codedeploy/stop.template > codedeploy/stop.sh
m4 -D_BUILD_ID_=$BUILD_ID codedeploy/healthcheck.template > codedeploy/healthcheck.sh
chmod +x codedeploy/start.sh
chmod +x codedeploy/stop.sh
chmod +x codedeploy/healthcheck.sh

aws deploy push \
                --application-name Sundial \
                --ignore-hidden-files \
                --s3-location s3://$CODEDEPLOY_S3_BUCKET/$DOCKER_REPO/${BUILD_ID}.zip \
                --source codedeploy/ \
                --description "Revision from $DOCKER_REPO build $BUILD_ID ."
rm -vf codedeploy/start.sh
rm -vf codedeploy/stop.sh
rm -vf codedeploy/healthcheck.sh
 
aws deploy create-deployment \
                --application-name Sundial \
                --deployment-group-name SundialProd \
                --s3-location bucket=$CODEDEPLOY_S3_BUCKET,key=$DOCKER_REPO/${BUILD_ID}.zip,bundleType=zip \
                --description "Revision from $DOCKER_REPO build $BUILD_ID ."
