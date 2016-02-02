# Sample job template

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
          "memory": 5000
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
    },
  ],
  "subscriptions": [
    {"name": "Dev Team", "email": "dev-team@organization.com"}
  ],
  "paused": false
}


POST this job template to http://<sundialurl>/api/process_definitions/SampleProcessName_