import org.springframework.cloud.contract.spec.Contract
Contract.make {
    name 'invalid_storage'
    request {
        method 'POST'
        url('/storages')
        headers { contentType(applicationJson()) }
        body('''{"storageType":"STAGING","bucket":"invalid_bucket","path":"/files"}''')
    }
    response {
        status 400
        headers { contentType(applicationJson()) }
        body('''{"errorMessage":"bucket must be a valid S3 bucket name","errorCode":"400"}''')
    }
}
