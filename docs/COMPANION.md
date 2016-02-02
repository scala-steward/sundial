
## Companion Container

Sundial uses a Docker image to provide a "companion container" to running tasks.  This container provides services
such as log replication and automatic metadata capture through a Graphite-compatible protocol.

You may occasionally need to update the companion container's scripts.  The project's scripts and Dockerfile are
under the `companion-container` folder.

To deploy your changes, run the `publish-companion.sh` script and update `conf/application.conf` to have the most
current companion tag (the `publish-companion.sh` script will provide you with the config line).

### How the Companion Container Works

ECS launches tasks via a "task definition", which contains one or more Docker container specifications (i.e.
specifying the arguments to `docker run`).  We must make sure that:

 * The companion container has access to the job container's logs
 * The companion container needs to know where to send its collected logs
 * The companion container offers metadata reporting to the job container via a network port
 * The companion container does not shut down until it has had the chance to report all of its accumulated logs

The arguments sent to ECS to create task definitions can be found in `ContainerServiceExecutor`.  The system does
the following for setup:

 * The companion container is set as the main container, so that the process only exits once it has finished
 * A mount point is provided for each log directory between the companion container and the job container
 * A link is provided between the two containers (which provides networking between them), and the address of
   the container's metadata protocol (which uses the Graphite format) is provided as an environment variable
   to the job container
 * A Cloudwatch Logs configuration file is built and stored in S3 based on the Sundial task ID
 * The companion container command is built as a bash command, so that the Sundial task ID can be sent via the
   `TASK_ID` environment variable
 * The companion container is provided a mount point to the ECS container instance's `/var/run` directory.  This
   allows the companion container to access the host's Docker daemon so that it can inspect the main job Docker
   container.

Then, the companion container script (`sundial-logs.sh`) does the following:

 * The metadata service is started (`metadata-server.py`), which opens a socket on port 13290 and sends any
   reported metadata (using the [Graphite plaintext protocol](http://graphite.readthedocs.org/en/latest/feeding-carbon.html#the-plaintext-protocol))
   to a SimpleDB record based on the `TASK_ID` environment variable (the records are stored in the 
   SimpleDB domain created through CFN template).  The Sundial server periodically polls this record for updates, 
   which are then copied into the central Sundial database.
 * The Cloudwatch Logs agent configuration is downloaded from S3 based on the `TASK_ID` environment variable, and
   the agent is installed and started, attaching to the log directories that have been mounted from the job container
 * The `/var/hostrun` directory, which has been mounted from the host's `/var/run` directory, contains `docker.sock`.
   The companion sends a raw command to the socket in order to determine what version of Docker is in use, as clients
   are often not backwards or forwards compatible (it uses `server-docker-version.py`).
 * The companion downloads the required Docker client version, first checking to see if it has been cached in our own
   (as Docker's servers are often very slow), and if not downloading it from the Docker site and stashing it in S3
   (in `install-docker.sh`).
 * The companion connects to the
   [ECS metadata service](http://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-agent-introspection.html)
   to collect information, but it cannot access it directly because it does not have shared host networking.  Because
   it has access to the Docker socket, it can launch a new utility Docker container (using [busybox](https://hub.docker.com/_/busybox/))
   that has shared host networking in order to access the ECS metadata service.  The command sent to the busybox
   container fetches task information from the metadata service.
   It uses its own Docker ID (extracted from `/proc/self/cgroup`) to determine which ECS task it is a part of.  It then
   determines the Docker ID of the job container based on the returned information.
 * The companion continuously polls `docker inspect` to wait for the job container to complete.
 * The companion collects all of the finished logs from its mounted log directories, puts them into a tarball and
   uploads them to an S3 object based on the `TASK_ID`
 * The companion waits 30 seconds, to make sure that the Cloudwatch Logs agent has time to flush.

