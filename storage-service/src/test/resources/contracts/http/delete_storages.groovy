import org.springframework.cloud.contract.spec.Contract
Contract.make {
    name 'delete_storages'
    request {
        method 'DELETE'
        url('/storages?id=3,99')
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''[3]''')
    }
}
