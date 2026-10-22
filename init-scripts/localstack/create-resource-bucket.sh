#!/bin/sh
set -eu

bucket="${RESOURCE_STORAGE_BUCKET:-resources}"
region="${AWS_DEFAULT_REGION:-us-east-1}"
if ! awslocal s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    if [ "$region" = "us-east-1" ]; then
        awslocal s3api create-bucket --bucket "$bucket" --region "$region"
    else
        awslocal s3api create-bucket --bucket "$bucket" --region "$region" \
            --create-bucket-configuration "LocationConstraint=$region"
    fi
fi
