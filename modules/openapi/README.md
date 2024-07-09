# Open API specs

The Open API specs of OpenID Federation.

## Entity Statement

An Entity Statement contains the information needed for the Entity that is the subject of the Entity Statement to 
participate in federation(s). An Entity Statement is a signed JWT. The subject of the JWT is the Entity itself. The 
issuer of the JWT is the party that issued the Entity Statement. All Entities in a federation publish an Entity Statement 
about themselves called an Entity Configuration. Superior Entities in a federation publish Entity Statements about their
Immediate Subordinate Entities called Subordinate Statements.

### Profiles

The Open API generator will generate only models, infrastructures and apis by default. To make it generate apis. To make
it generate models only uncomment `profiles=models-only` from gradle.properties or pass the profile in the comment line.

### Run Open API generator

Generate models, infrastructures and apis:
```shell
gradle clean openApiGenerate
```

Generate only models:
```shell
gradle clean openApiGenerate -Pprofile=model-only
```
