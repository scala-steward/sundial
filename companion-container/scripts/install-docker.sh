#!/bin/sh -x

# SINCE we don't necessarily know what version of Docker is running on the server,
# we have to adaptively install the correct version.

SERVER_DOCKER_VERSION=$(./server-docker-version.py)

# Check to see if we already have this version cached in S3
# Check to see if we already have this version cached in S3
n=0
until [ $n -ge 5 ]
do
        aws s3 cp s3://$RESOURCE_S3Bucket/docker/docker-$SERVER_DOCKER_VERSION.tgz /tmp/docker.tgz && break
        n=$((n + 1))
        sleep 10
done

if ! [[ -s /tmp/docker.tgz ]]; then
    echo "Docker client version $SERVER_DOCKER_VERSION.tgz was not cached in S3 - downloading..."
        n=0
        until [ $n -ge 5 ]
        do
                wget -O /tmp/docker.tgz https://get.docker.com/builds/Linux/x86_64/docker-$SERVER_DOCKER_VERSION.tgz && break
                n=$((n + 1))
                sleep 10
        done
        n=0
        until [ $n -ge 5 ]
        do
            aws s3 cp /tmp/docker.tgz s3://$RESOURCE_S3Bucket/docker/docker-$SERVER_DOCKER_VERSION.tgz && break
                n=$((n + 1))
                sleep 10
        done
fi

tar xfzv /tmp/docker.tgz -C /tmp
chmod +x /tmp/docker/*
mv /tmp/docker/* /usr/local/bin
