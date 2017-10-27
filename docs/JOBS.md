# Sample job template
### Using ECS (docker_image_command executable)
```json
{
  "process_definition_name": "SampleProcessName",
  "process_description": "Sample process description",
  "overlap_action": "terminate",
  "schedule": {
     "continuous_schedule": {
        "buffer_seconds": 1200
     }
  },
  "task_definitions": [
    {
      "task_definition_name": "starting-task",
      "executable": {
        "docker_image_command": {
          "image": "dockerregistryurl/imagename",
          "tag": "0.0.1",
          "command": ["jobstart.sh"],
          "log_paths": ["/opt/job/log/application.log"],
          "cpu": 1000,
          "memory": 5000,
          "environment_variables" : [
            {
              "variable_name": "EnvironmentType",
              "value": "production"
            }
          ]
        }
      },
      "dependencies": [],
      "max_attempts": 2,
      "backoff_base_seconds": 60,
      "backoff_exponent": 1,
      "require_explicit_success": false,
      "max_runtime_seconds": 1200
    },
    {
      "task_definition_name": "dependent-task",
      "executable": {
        "docker_image_command": {
          "image": "dockerregistryurl/imagename",
          "tag": "0.0.1",
          "command": ["jobstart.sh"],
          "log_paths": ["/opt/job/log/application.log"],
          "cpu": 1000,
          "memory": 5000
        }
      },
      "dependencies": [
        {"task_definition_name": "starting-task", "success_required": true}
      ],
      "max_attempts": 2,
      "backoff_base_seconds": 60,
      "backoff_exponent": 1,
      "require_explicit_success": false,
      "max_runtime_seconds": 1200
    }
  ],
  "subscriptions": [
    {"name": "Dev Team", "email": "dev-team@organization.com", "notify_when": "on_state_change_and_failures"}
  ],
  "paused": false
}
```
### Using AWS Batch (batch_image_command executable) -- recommended

```json
{"executable": {
        "batch_image_command": {
          "image": "dockerregistryurl/imagename",
          "tag": "0.0.1",
          "command": [],
          "vCpus": 1,
          "memory": 1000,
           "job_role_arn": "<<IAM role for job (see details below)>>",
          "environment_variables": []
        }
      }
      }
```

PUT this job template to http://sundialurl/api/process_definitions/SampleProcessName

# Explanation of parameters

* **process_definition_name**: Identifier for your process. Should match what you use in POST URL when submitting
* **process_definition**: Whatever you like
* **overlap_action**: Specifies what happens to old running process if new process kicks off. Options are "wait" or "terminate"
* **schedule**: Optional. If absent, need to trigger process run manually through UI. Options are "continuous_schedule" where process runs every X number of seconds, or "cron_schedule" where process runs on a Cron schedule.
* **subscriptions**: List of people who will receive e-mail notification when process finishes
* **paused**: Whether to pause scheduling of process. This can be toggled through UI.
* **task_definitions**: List of tasks within the process
* **task_definition_name**: Identifier for the task
* **executable**: This can be a Docker container or a shell command
* **image**: For Docker images path to registry and docker image. eg: docker-registry-url/imagename
* **tag**: Tag of Docker image. Could use "latest" but better to be explicit and update the process definition at the same time as deploying new Docker image.
* **command**: Array of commands to be passed as Docker CMD parameter
* **log_paths**: Location of application logs within the Docker container. Sundial will stream these logs to Cloudwatch for live viewing and also collect the logs at end of task run and upload to S3.
* **cpu**: How much CPU to allocate on ECS instace
* **memory**: How much memory to allocate on ECS instance
* **environment_variables**: These will be passed as environment variables to running container
* **dependencies**: Tasks that need to run before this task runs
* **max_attempts**: Maximum number of times this task can be run if task fails
* **backoff_base_seconds**: How long to wait before retrying
* **backoff_exponent**: Enables exponential backoff
* **require_explicit_success**: If this is set to true, job will need to make a call to Sundial REST API to explicity signal that it has successfully completed. The URL of Sundial is exposed to the running job as an environment variable called sundial.url
* **max_runtime_seconds**: Maximum number of seconds this task is allowed to run for before Sundial kill it.

More descriptions of the options and client generators for Scala, Ruby, Java, NodeJs are available at http://apidoc.me/gilt/svc-sundial/latest, or http://ui-www.apibuilder.io.s3-website-us-east-1.amazonaws.com/org/gilt/app/svc-sundial

# Creating IAM role for job

When using AWS Batch executable, create an IAM role for the job or use existing one if it is safe to do so. 

Make sure to modify the policy document with trust relationships (for the IAM role) to include permissions for ec2, ecs-tasks and the job instanceEC2 role (that Sundial has created for its compute environment). Here is a sample policy document-
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    },
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    },
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "batch.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    },
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789:role/sundial-JobInstanceEC2Role-a1b2c3d4e5"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```