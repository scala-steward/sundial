#!/bin/sh

: "${DOCKER_REGISTRY?Need to set DOCKER_REGISTRY}"
: "${ECS_COMPANION_TAG?Need to set ECS_COMPANION_TAG}"
# An s3 bucket for deployment assets
: "${CODEDEPLOY_S3_BUCKET?Need to set CODEDEPLOY_S3_BUCKET}"
: "${NOTIFICATIONS_EMAIL_FROM?Need to set $NOTIFICATIONS_EMAIL_FROM}"

#Change these to match your environment
REGION=us-east-1
DOCKER_REPO=sundial

sbt docker:publishLocal

#Only needed if you're using EC2 Container Registry
$(aws ecr get-login --no-include-email --region $REGION)

BUILD_ID=$(git describe --tags --dirty --always | sed "s/^v//")

docker tag $DOCKER_REPO:$BUILD_ID $DOCKER_REGISTRY/$DOCKER_REPO:$BUILD_ID

docker push $DOCKER_REGISTRY/$DOCKER_REPO:$BUILD_ID

echo "BUILD_ID: $BUILD_ID"
echo "DOCKER_REPO: $DOCKER_REPO"
echo "DOCKER_REGISTRY: $DOCKER_REGISTRY"
echo "ECS_COMPANION_TAG:  $ECS_COMPANION_TAG"
echo "CODEDEPLOY_S3_BUCKET:  $CODEDEPLOY_S3_BUCKET"

m4 -D_BUILD_ID_=$BUILD_ID -D_DOCKER_REPO_=$DOCKER_REPO -D_DOCKER_REGISTRY_=$DOCKER_REGISTRY -D_ECS_COMPANION_TAG_=$ECS_COMPANION_TAG -D_NOTIFICATIONS_EMAIL_FROM_="$NOTIFICATIONS_EMAIL_FROM" codedeploy/start.template > codedeploy/start.sh
m4 -D_BUILD_ID_=$BUILD_ID codedeploy/healthcheck.template > codedeploy/healthcheck.sh
chmod +x codedeploy/start.sh
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
