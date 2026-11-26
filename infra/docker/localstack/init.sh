#!/usr/bin/env bash
set -euo pipefail
for bucket in dockvue-media dockvue-artifacts dockvue-registry dockvue-staging; do
  awslocal s3api head-bucket --bucket "$bucket" 2>/dev/null || awslocal s3api create-bucket --bucket "$bucket" --create-bucket-configuration LocationConstraint=ap-northeast-2
done
awslocal s3api put-bucket-cors --bucket dockvue-media --cors-configuration '{"CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag","x-amz-checksum-sha256"],"MaxAgeSeconds":600}]}'
awslocal s3api put-bucket-lifecycle-configuration --bucket dockvue-media --lifecycle-configuration '{"Rules":[{"ID":"abort-incomplete","Status":"Enabled","Filter":{"Prefix":"videos/"},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
for queue in dockvue-media dockvue-build; do
  dlq=$(awslocal sqs create-queue --queue-name "$queue-dlq" --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
  arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  attributes=$(python3 -c 'import json,sys; print(json.dumps({"VisibilityTimeout":"120","ReceiveMessageWaitTimeSeconds":"20","MessageRetentionPeriod":"345600","RedrivePolicy":json.dumps({"deadLetterTargetArn":sys.argv[1],"maxReceiveCount":"5"})}))' "$arn")
  awslocal sqs create-queue --queue-name "$queue" --attributes "$attributes"
done
