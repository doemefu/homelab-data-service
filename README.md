### data-service

**Domain:** Data retrieval and schedule management.

**Responsibilities:**
- InfluxDB queries (historical temperature, humidity, device status)
- Schedule CRUD REST API (create, read, update, delete schedules)
- JWT validation for all endpoints

**Does NOT:**
- Connect to MQTT
- Write to InfluxDB
- Manage users or issue tokens

**Database:** PostgreSQL — `schedules` table (owns it)
**InfluxDB:** Read-only (queries)

**Key design decision:** Schedule CRUD lives here (not in device-service) because it is a REST-driven, human-facing concern. The device-service reads schedules from the shared DB table. This keeps device-service focused on its real-time loop and avoids exposing user-facing REST APIs from a service that should be headless.

