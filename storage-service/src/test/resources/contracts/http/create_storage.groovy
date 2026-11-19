import org.springframework.cloud.contract.spec.Contract
Contract.make {
    name 'create_storage'
    request {
        method 'POST'
        url('/storages')
        headers { contentType(applicationJson()) }
        body('''{"storageType":"STAGING","bucket":"custom-bucket","path":"/files"}''')
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''{"id":3}''')
    }
}
