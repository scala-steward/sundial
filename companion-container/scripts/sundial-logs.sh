#!/bin/bash

function upload_logs {
	echo "Uploading logs..."

    # Persist output of docker inspect and docker logs for log collector to pick up

	DOCKER_ID=$(cat dockerid.txt)

	/usr/local/bin/docker --host unix:///var/hostrun/docker.sock inspect $DOCKER_ID > /var/log/sundial/task-logs-0/dockerinspect.json
	/usr/local/bin/docker --host unix:///var/hostrun/docker.sock logs $DOCKER_ID > /var/log/sundial/task-logs-0/stdouterr.log 2>&1

	# Upload the logs to S3
	# Merge log directories together and tar
	find /var/log/sundial -maxdepth 1 -mindepth 1 -type d | xargs -I {} -n 1 tar -r -f /tmp/logs.tar -C {} .

	n=0
	until [ $n -ge 5 ]
	do
		aws s3 cp /tmp/logs.tar s3://$RESOURCE_S3Bucket/logs/$TASK_ID && break
		n=$((n + 1))
		sleep 10
	done	

	# Give the Cloudwatch agent time to flush
	sleep 30
}

chmod a+rw /var/log/sundial/*

$(./cfn-env.sh)

#
# The Sundial task ID must be in the SUNDIAL_TASK_ID environment variable
#

CLOUDWATCH_LOGS_CONFIG_FILE="s3://$RESOURCE_S3Bucket/agent-config/$TASK_ID"

echo "Starting the metadata server..."
chmod +x ./metadata-server.py
./metadata-server.py $TASK_ID &

echo "Installing Cloudwatch Logs agent with config at $CLOUDWATCH_LOGS_CONFIG_FILE..."
wget https://s3.amazonaws.com/aws-cloudwatch/downloads/latest/awslogs-agent-setup.py
chmod +x awslogs-agent-setup.py
./awslogs-agent-setup.py -n -r us-east-1 -c $CLOUDWATCH_LOGS_CONFIG_FILE

echo "Cloudwatch Logs agent set up with following config:"
cat /var/awslogs/etc/awslogs.conf
echo ""

echo "Installing the Docker client..."
./install-docker.sh

echo "Waiting for main container to exit..."

trap upload_logs SIGTERM

# The agent runs in the background; now, we need to wait until our sibling task finishes,
# then upload the logs to S3.

./await-main-container.py

upload_logs
