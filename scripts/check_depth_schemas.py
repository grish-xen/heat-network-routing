"""Validate handoff fixtures against shared schemas; install requirements-checks.txt first."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator
from openapi_schema_validator import OAS30Validator

ROOT = Path(__file__).resolve().parents[1]


def read(path): return json.loads((ROOT / path).read_text(encoding='utf-8'))


def main():
    api = read('contracts/openapi.json')
    # All references are repository-local OpenAPI components. Resolve before
    # validating to avoid a network resolver and avoid deprecated RefResolver.
    def resolve(value):
        if isinstance(value,dict):
            if '$ref' in value:
                target = api
                if not value['$ref'].startswith('#/'): raise ValueError('External schema reference')
                for part in value['$ref'][2:].split('/'): target = target[part]
                return resolve(target)
            return {k:resolve(v) for k,v in value.items()}
        if isinstance(value,list): return [resolve(v) for v in value]
        return value
    def validate(name, value):
        schema = resolve(api['components']['schemas'][name])
        OAS30Validator(schema).validate(value)
    folder = ROOT / 'test-data/api/depth'
    for path in folder.glob('job-*.json'): validate('Job', json.loads(path.read_text(encoding='utf-8')))
    for summary in read('test-data/api/depth/variants.json'): validate('VariantSummary',summary)
    validate('MapBounds', read('test-data/api/depth/map-bounds.json'))
    for name in ('map-input.json','map-result.json'): validate('MapPage', read('test-data/api/depth/'+name))
    validate('Error',read('test-data/api/depth/unsupported-mode.json'))
    for name in read('test-data/synthetic/depth/manifest.json')['cases']:
        for feature in read('test-data/synthetic/depth/'+name+'/expected.geojson')['features']:
            if feature['properties']['object_type'] == 'variant_summary':
                validate('VariantSummary', feature['properties'])
            else:
                validate('MapFeature',feature)
                if feature['properties']['object_type'] == 'heat_network':
                    validate('DepthNetworkProperties',feature['properties'])
    schema = read('contracts/visualization-rules.schema.json')
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(read('contracts/visualization-rules.v1.json'))
    print('OK: all depth API/GeoJSON fixtures and visualization resource match their schemas.')


if __name__ == '__main__': main()
