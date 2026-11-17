# Integración de rutas OSRM

## Arquitectura

Angular solicita rutas únicamente a la API Java mediante `/api/rutas/**`. El backend
consulta el servicio OSRM privado, transforma las coordenadas GeoJSON
`[longitud, latitud]` a pares `latitud/longitud` y responde con geometría, distancia
y duración. No se debe configurar el navegador para llamar a OSRM.

El `frontend/firebase.json` existente ya reescribe `/api/**` al servicio
`oli-track-v-1-0-0-1-git` en `us-central1`. Esa configuración no se cambió.
No hay un entorno staging habilitado: no se proporcionaron un proyecto Firebase
de pruebas ni un backend staging. No compiles ni publiques un staging con la
configuración de producción o con claves de marcador de posición.

## Ejecución local

En una terminal PowerShell para el backend:

```powershell
$env:OSRM_BASE_URL = "http://localhost:5000"
$env:OSRM_IAM_ENABLED = "false"
.\mvnw.cmd spring-boot:run
```

En otra terminal, desde `frontend`:

```powershell
npm start -- --host 0.0.0.0
```

El proxy de desarrollo reenvía `/api` a `http://localhost:8080`. Para abrir desde
otro dispositivo de la misma red, ambos deben poder alcanzar el equipo que ejecuta
Angular y el backend; no se cambia ninguna configuración de producción con estos
comandos.

## Cloud Run e IAM requeridos

La cuenta de ejecución verificada para el backend es
`firebase-adminsdk-fbsvc@olitrack-f0fcc.iam.gserviceaccount.com`. La URL canónica
verificada de OSRM, que también se usa como audiencia del ID token, es:

```text
https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app
```

Se verificó la política IAM de `osrm-ayacucho-test`: originalmente solo la cuenta
de staging tenía `roles/run.invoker`. Con autorización, se agregó ese rol a la
cuenta de ejecución del backend, limitado al servicio OSRM:

```powershell
gcloud run services add-iam-policy-binding osrm-ayacucho-test `
  --project olitrack-f0fcc `
  --region us-central1 `
  --member="serviceAccount:firebase-adminsdk-fbsvc@olitrack-f0fcc.iam.gserviceaccount.com" `
  --role="roles/run.invoker"
```

La política se volvió a consultar y confirmó ambas cuentas como invocadoras:
`firebase-adminsdk-fbsvc@olitrack-f0fcc.iam.gserviceaccount.com` y
`olitrack-osrm-staging@olitrack-f0fcc.iam.gserviceaccount.com`. OSRM continúa
requiriendo autenticación IAM; no se hizo público ni se alteraron permisos de otros
servicios.

La inspección de solo lectura del servicio de producción confirmó que el backend
usa la cuenta de ejecución indicada y que actualmente solo tiene definida la
variable `CORS_ALLOWED_ORIGINS`; `OSRM_BASE_URL` y `OSRM_IAM_ENABLED` todavía no
están configuradas. La política IAM de OSRM ya permite invocar el servicio a la
cuenta de ejecución del backend.

La llamada autenticada real a OSRM sigue sin verificarse. Se intentó una
comprobación temporal en Cloud Run con la identidad del backend, pero la ejecución
`olitrack-osrm-check-20261117-1530-vfpht` terminó con código 1 porque el contenedor
no arrancó; no llegó a solicitar OSRM ni produjo un estado HTTP o geometría. El
trabajo temporal `olitrack-osrm-check-20261117-1530` se eliminó después. No se
concedió `iam.serviceAccounts.getAccessToken` a Cloud Shell ni se cambiaron permisos
adicionales. La clave pública de Firebase se comprobó contra el endpoint de
configuración de Authentication sin crear usuarios; fue aceptada.

La configuración predeterminada del backend ahora apunta a este servicio privado
y activa IAM. Cloud Run puede usarla directamente siempre que conserve la cuenta
de ejecución verificada. Si prefieres dejar los valores explícitos en la
configuración del servicio, usa:

```text
OSRM_BASE_URL=https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app
OSRM_IAM_ENABLED=true
CORS_ALLOWED_ORIGINS=https://olitrack-f0fcc.web.app,https://olitrack-f0fcc.firebaseapp.com
```

`OSRM_TIMEOUT_MS` y `OSRM_MAX_TABLE_CANDIDATES` son opcionales; sus valores por
defecto son `5000` y `50`. El backend obtiene el ID token desde ADC y no requiere
archivos JSON de claves privadas.

## Rutas, permisos y límites

- `GET /api/rutas/pedidos/{pedidoId}` comprueba que clientes y repartidores solo
  consulten pedidos propios o asignados. Administradores y supervisores pueden
  consultarlos todos.
- `GET /api/rutas/calcular` y `POST /api/rutas/comparar-repartidores` requieren
  rol ADMINISTRADOR o SUPERVISOR.
- La comparación usa OSRM Table y muestra una recomendación por menor tiempo vial;
  no asigna ni reasigna pedidos.
- Si OSRM no encuentra ruta o no cubre las coordenadas, la interfaz informa
  “Ruta vial no disponible” y no dibuja una línea recta.
- Las actualizaciones de rutas activas se limitan a un movimiento GPS de 50 m y a
  un máximo de tres solicitudes simultáneas por mapa.

## Validación y publicación

Validar localmente antes de cualquier publicación:

```powershell
# Desde backend
.\mvnw.cmd test

# Desde frontend
npm test -- --watch=false
npm run build
```

Al subir manualmente una nueva revisión, conserva la cuenta de ejecución actual
`firebase-adminsdk-fbsvc@olitrack-f0fcc.iam.gserviceaccount.com`. En la consola de
Cloud Run, configura estas variables en la nueva revisión y conserva
las demás variables existentes. Define `CORS_ALLOWED_ORIGINS` con ambos dominios
autorizados, como se muestra arriba.

El diagnóstico anterior confirmó que el backend original bloquea solicitudes en
Google Front End (403) antes de llegar a Java. Para que Firebase Hosting pueda
reenviar `/api/**`, habilita invocación pública **solo en el backend OLITRACK**;
Spring Security seguirá validando Firebase y los roles. No hagas público OSRM.
Después de autorizar este cambio, el comando es:

```powershell
gcloud run services update oli-track-v-1-0-0-1-git `
  --project olitrack-f0fcc `
  --region us-central1 `
  --no-invoker-iam-check
```

Ese permiso es para el backend OLITRACK, no para OSRM; la autenticación Firebase
y las reglas Spring por rol siguen siendo obligatorias. El frontend ya usa `/api`
y `firebase.json` lo reescribe al servicio de Cloud Run: no configures una URL de
Cloud Run u OSRM directamente en Angular. Después de verificar la API, crea el
build de producción para subirlo manualmente:

```powershell
Set-Location "C:\Users\USER\Documents\tesis\OLI FRESH ICE\OLITRACK\frontend"
npm run build
firebase deploy --only hosting --project olitrack-f0fcc
```

El permiso IAM de invocación descrito arriba ya fue aplicado y verificado. Las
pruebas locales pasaron (30 Java; 8 Angular y build). El despliegue de Cloud Run y
`firebase deploy` requieren autorización final y no se ejecutaron.
