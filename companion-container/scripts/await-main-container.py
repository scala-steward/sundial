#!/usr/bin/python

import os
import json
import time

my_docker_id = os.popen("cat /proc/self/cgroup | grep 'cpu:/docker/' | grep -o '[^/]*$'").read().rstrip()
other_container_docker_id = None

while other_container_docker_id is None:
    print("Querying tasks info from ECS")
    agent_info_raw = os.popen("docker --host unix:///var/hostrun/docker.sock run --net=host busybox wget -qO- http://localhost:51678/v1/tasks").read()
    print("Response from ECS: " + agent_info_raw)
    try:
        agent_info = json.loads(agent_info_raw)
        print("Looking for the other container's ID...")
        # Iterate through the tasks and find the one that has a container matching our docker ID
        current_task = None
        for task in agent_info['Tasks']:
            for container in task['Containers']:
                if container['DockerId'] == my_docker_id:
                    current_task = task

        # Now, the container we are monitoring is the one in this task that is not this container
        other_container = None
        for container in current_task['Containers']:
            if container['DockerId'] != my_docker_id:
                other_container = container

        if other_container is not None:
            other_container_docker_id = other_container['DockerId']
        else:
            time.sleep(5)
    except ValueError:
        print("Error decoding JSON response from ECS agent")
        time.sleep(5)   

if other_container_docker_id:
    with open('dockerid.txt', 'w') as docker_id_file:
        docker_id_file.write(other_container_docker_id)

# Wait until the container has exited
ready = False
while not ready:
    if not other_container_docker_id:
        print("Other container's docker ID is empty, so it exited before we started.")
        ready = True
    else:
        print("Waiting on container %s to exit...\n" % other_container_docker_id)
        time.sleep(5)
        is_running = os.popen("docker --host unix:///var/hostrun/docker.sock inspect --format '{{ .State.Running }}' %s" % other_container_docker_id).read().rstrip()
        ready = (is_running == 'false')

