# Admin server

API
<br>
```/status``` - To check health status

<br>

Add environment file (.env) with following properties
```
DATABASE_USER=<USER>
DATABASE_PASSWORD=<PAsSWORD>
DATABASE_NAME=<NAME>
```

To build
<br>
```./gradlew admin-server:build```

To run
<br>
```./gradlew admin-server:bootRun```

To run tests
<br>
```./gradlew admin-server:test```