import org.springframework.cloud.contract.spec.Contract
Contract.make {
    name 'list_storages'
    request {
        method 'GET'
        url('/storages')
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''[{"id":1,"storageType":"STAGING","bucket":"resources-staging","path":"/files"},{"id":2,"storageType":"PERMANENT","bucket":"resources-permanent","path":"/files"}]''')
    }
}
