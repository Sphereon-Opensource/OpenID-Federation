# Admin server

API
<br>
```/status``` - To check health status

<br>

Add environment file (.env) with following properties

```
DATASOURCE_USER=<USER>
DATASOURCE_PASSWORD=<PASSWORD>
DATASOURCE_URL=<URL>
```

To build
<br>
```./gradlew :modules:openid-federation-admin-server:build```

To run
<br>
```./gradlew :modules:openid-federation-admin-server:bootRun```

To run tests
<br>
```./gradlew :modules:openid-federation-admin-server:test```
