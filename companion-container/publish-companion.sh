#!/bin/sh

cd "$(dirname "$0")"

REGION=us-east-1 # do not change this since ECR is available only in us-east-1
#Address of your private Docker Registry. (Recommend you use Amazon container registry)
DOCKER_REGISTRY=
DOCKER_REPO=sundial-companion

TAG_NAME=$(date +%s)
$(aws ecr get-login --region $REGION)

docker build -t $DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME . &&
docker push $DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME

echo "Make sure to update the Sundial application.conf with:"
echo "companion.tag=\"$DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME\""
