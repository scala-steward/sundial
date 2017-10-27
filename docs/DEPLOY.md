## Deployment Setup

There are 3 steps to deplolying Sundial.

The first is deploying the companion container.

The companion container is located within the companion-container/ directory in this project. It is recommended you deploy to Amazon Container Registry (https://aws.amazon.com/ecr/) or a private hosted Docker registry as Sundial currently doesn't support username/password based authentication on registries.

Edit publish-companion-container.sh script and replace variables with details of your own registry. Run the script.
When the script has finished, it will output the full path to the companion container in your Docker registry. Edit conf/application.conf and replace the companion container path with the path to your version. 

The second step is creating the Sundial stack. 
Inside aws/ directory is a file called cfn.json . This is the Cloudformation template to be deployed using Amazon Cloudformation. 
Go to Amazon AWS Console -> Cloudformation -> Create Stack. Choose "Upload a template to Amazon S3" and point it to this cfn.json file. Fill out the parameters on the next page. You will need to know your VPC id as well as the private Subnet ids for your VPC.

**Note: When creating Cloudformation stack, it might help (debugging and troubleshooting) to make both the ELB and service subnet private.** 

The Cloudformation template will create your RDS metadata instance, your ECS cluster, an S3 bucket for logs and a service instance for the Sundial Web UI.

The last step is to deploy the Sundial service and UI. Create an ECS registry for sundial. A deploy.sh script is provided to upload deploy scripts to S3, Dockerized version of service to your Docker registry and trigger Amazon Codedeploy to pull the image and deploy it to your stack. Make sure you edit deploy.sh and application.conf and give it details of your AWS region, Docker registry etc.

# Deployment parameters

Stack name: Whatever you like. Descriptive name for the stack in Cloudformation

DBAllocated storage: Amount of storage space to give to RDS instance that stores job metadata and process logs

DBInstanceClass: A small instance like t2.small should be fine here. We don't need high performance from RDS

DBName: Fine to leave as default

DBPassword: self explanatory

DBSubnetGroup: Trickiest parameter: Need to look this up under AWS Console -> RDS -> Subnet groups. If a suitable one does not exist, create one that includes all the subnets in your VPC that match the subets your service instance will run on.

DBUSername: Fine to leave as default.

ELBScheme: Chances are you want internet-facing unless your organization has a means of routing requests to ELBs in private VPC subnet group.

ELBSubnets: Choose public subnets if you chose internet-facing ELB, private subets if you chose internal ELB

HostedZoneName: Name of hosted zone to set up DNS entry in. Look this up under Route53 in console. Use full name including periods.

JobClusterSize: Number of job instances you want in your ECS cluster

JobInstanceFilesystemDiskSpace: Amount of EBS space to allocate to each job instance.

JobInstanceFilesystemDiskType:

JobInstanceDockerDiskSpace: Amount of EBS space to allocate to each job instance's docker containers.

JobInstanceDockerDiskType:

JobInstanceType:
