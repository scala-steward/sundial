#!/bin/sh

: "${DOCKER_REGISTRY?Need to set DOCKER_REGISTRY}"

cd "$(dirname "$0")"

REGION=us-east-1 # do not change this since ECR is available only in us-east-1
#Address of your private Docker Registry. (Recommend you use Amazon container registry)
DOCKER_REPO=sundial-companion

TAG_NAME=$(date +%s)
$(aws ecr get-login --region $REGION)

echo "DOCKER_REGISTRY: $DOCKER_REGISTRY"
echo "DOCKER_REPO: DOCKER_REPO"
echo "TAG_NAME: $TAG_NAME"

docker build -t $DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME . &&
docker push $DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME

echo "Make sure to set the Sundial Docker ENV with:"
echo "ECS_COMPANION_TAG=\"$DOCKER_REGISTRY/$DOCKER_REPO:$TAG_NAME\""
