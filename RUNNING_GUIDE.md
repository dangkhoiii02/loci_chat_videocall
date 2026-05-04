# Chạy Đồ Án Loci Chat

## Yêu cầu
- Java 17+, Maven, Node.js 18+, Docker

---

## 1. Khởi động hạ tầng

```bash
cd local-dev
docker compose up -d
```

---

## 2. Bật plugin RabbitMQ *(chỉ lần đầu)*

```bash
docker exec loci-mq rabbitmq-plugins enable rabbitmq_stomp rabbitmq_web_stomp rabbitmq_management
```

---

## 3. Tạo bucket MinIO *(chỉ lần đầu)*

Vào **http://localhost:9001** (admin / password) → tạo bucket tên **`loci-bucket-01`**

---

## 4. Tạo file `loci-backend/.env`

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/locidb
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=admin
SPRING_FLYWAY_URL=jdbc:postgresql://localhost:5432/locidb
SPRING_FLYWAY_USER=admin
SPRING_FLYWAY_PASSWORD=admin

KEYCLOAK_ISSUER_URI_HOST=http://localhost:9093
KEYCLOAK_JWK_SET_URI_HOST=http://localhost:9093
KEYCLOAK_REALM=loci-realm
KEYCLOAK_CREDENTIALS_USERNAME=admin
KEYCLOAK_CREDENTIALS_PASSWORD=admin
KEYCLOAK_MIGRATION_PASSWORD=exampleloci123

SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=admin
SPRING_RABBITMQ_PASSWORD=admin
STOMP_RELAY_HOST=localhost
STOMP_RELAY_PORT=61613
STOMP_RELAY_CLIENT_LOGIN=admin
STOMP_RELAY_CLIENT_PASSCODE=admin
STOMP_RELAY_SYSTEM_LOGIN=admin
STOMP_RELAY_SYSTEM_PASSCODE=admin

UPLOAD_MINIO_URL=http://localhost:9000
UPLOAD_MINIO_URL_PREFIX=http://localhost:9000
UPLOAD_MINIO_ACCESS_KEY=admin
UPLOAD_MINIO_SECRET_KEY=password
UPLOAD_MINIO_BUCKET=loci-bucket-01

LIVEKIT_URL=ws://localhost:7880
LIVEKIT_API_KEY=devkey
LIVEKIT_API_SECRET=secret
```

---

## 5. Chạy Backend

```bash
cd loci-backend
./mvnw spring-boot:run -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true -Dspotbugs.skip=true
```

---

## 6. Chạy Frontend

```bash
cd loci-frontend
npm install      # chỉ lần đầu
npm start
```

Truy cập: **http://localhost:4200**

---

## Cổng dịch vụ

| Dịch vụ | URL | Tài khoản |
|---|---|---|
| Frontend | http://localhost:4200 | — |
| Keycloak Admin | http://localhost:9093 | admin / admin |
| RabbitMQ | http://localhost:15672 | admin / admin |
| MinIO | http://localhost:9001 | admin / password |

Terminal 1 — hạ tầng (chạy 1 lần):

cd local-dev && docker compose up -d
Terminal 2 — Backend:

cd loci-backend
./mvnw spring-boot:run -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true -Dspotbugs.skip=true

Đợi thấy log WebSocket/STOMP đang nhận kết nối là OK.

Terminal 3 — Frontend:

cd loci-frontend
npm start

Mở http://localhost:4200

Nếu backend báo port 8080 đang bị chiếm, chạy trước:



kill -9 $(lsof -ti :8080)