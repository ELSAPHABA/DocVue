"""Cross-platform Phase 01 smoke test; run against the local Compose stack."""
import json
import subprocess
import urllib.request


def compose(*args):
    result = subprocess.run(
        ['docker', 'compose', '--profile', 'core', *args],
        check=True, capture_output=True, text=True, encoding='utf-8', timeout=60,
    )
    return result.stdout.strip()


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def main():
    for service, internal in [('core-api', '8080'), ('realtime-api', '8081')]:
        binding = compose('port', service, internal)
        host, port = binding.rsplit(':', 1)
        assert host == '127.0.0.1', f'unexpected binding: {binding}'
        url = f'http://{binding}'
        for path in ('/actuator/health/readiness', '/actuator/health/liveness'):
            assert get_json(url + path)['status'] == 'UP', (service, path)
        assert get_json(url + '/api/v1/status') == {'service': service, 'phase': '01'}
        print(f'PASS {service} readiness/liveness/status via {binding}')
    migration = compose('exec', '-T', 'mysql', 'sh', '-c',
                        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u dockvue -N -B dockvue -e "SELECT schema_label FROM platform_schema_metadata WHERE id=1"')
    assert migration == 'phase-01', migration
    print('PASS MySQL Flyway migration')
    assert compose('exec', '-T', 'redis', 'redis-cli', 'ping') == 'PONG'
    print('PASS Redis ping')
    assert compose('exec', '-T', 'mongo', 'mongosh', '--quiet', '--eval', "db.adminCommand('ping').ok") == '1'
    print('PASS MongoDB ping')
    for bucket in ('dockvue-media', 'dockvue-artifacts', 'dockvue-registry', 'dockvue-staging'):
        compose('exec', '-T', 'localstack', 'awslocal', 's3api', 'head-bucket', '--bucket', bucket)
        print(f'PASS LocalStack S3 {bucket}')
    for queue in ('dockvue-media', 'dockvue-build'):
        url = json.loads(compose('exec', '-T', 'localstack', 'awslocal', 'sqs', 'get-queue-url', '--queue-name', queue))['QueueUrl']
        dead_url = json.loads(compose('exec', '-T', 'localstack', 'awslocal', 'sqs', 'get-queue-url', '--queue-name', queue + '-dlq'))['QueueUrl']
        attr = json.loads(compose('exec', '-T', 'localstack', 'awslocal', 'sqs', 'get-queue-attributes', '--queue-url', url, '--attribute-names', 'All'))['Attributes']
        dead = json.loads(compose('exec', '-T', 'localstack', 'awslocal', 'sqs', 'get-queue-attributes', '--queue-url', dead_url, '--attribute-names', 'All'))['Attributes']
        policy = json.loads(attr['RedrivePolicy'])
        assert attr['VisibilityTimeout'] == '120' and attr['ReceiveMessageWaitTimeSeconds'] == '20'
        assert attr['MessageRetentionPeriod'] == '345600' and dead['MessageRetentionPeriod'] == '1209600'
        assert int(policy['maxReceiveCount']) == 5 and policy['deadLetterTargetArn'] == dead['QueueArn']
        print(f'PASS LocalStack SQS {queue} and DLQ policy')


if __name__ == '__main__':
    main()
