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
mvn spring-boot:run
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

Antes de configurar el backend, consultar la identidad actualmente asignada a
Cloud Run y conservarla; el backend la usa mediante ADC para Firebase:

```powershell
$BACKEND_SA = gcloud run services describe oli-track-v-1-0-0-1-git `
  --project olitrack-f0fcc `
  --region us-central1 `
  --format="value(spec.template.spec.serviceAccountName)"

gcloud run services describe osrm-ayacucho-test `
  --project olitrack-f0fcc `
  --region us-central1 `
  --format="value(status.url)"
```

La URL OSRM esperada para `OSRM_BASE_URL` y como audiencia del ID token es:

```text
https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app
```

Si la cuenta de ejecución actual no tiene `roles/run.invoker` en el servicio OSRM,
el administrador debe conceder el permiso **solo sobre `osrm-ayacucho-test`**:

```powershell
gcloud run services add-iam-policy-binding osrm-ayacucho-test `
  --project olitrack-f0fcc `
  --region us-central1 `
  --member="serviceAccount:$BACKEND_SA" `
  --role="roles/run.invoker"
```

No ejecutar ese cambio hasta revisar que `$BACKEND_SA` sea la identidad existente y
que conserve sus permisos actuales de Firebase. No hacer público OSRM ni cambiar su
política de acceso. La configuración actual de la cuenta del backend no pudo
consultarse en esta máquina porque `gcloud` no está instalado.

Una vez autorizada la configuración de Cloud Run, establecer en el backend:

```text
OSRM_BASE_URL=https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app
OSRM_IAM_ENABLED=true
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
mvn test

# Desde frontend
npm test -- --watch=false
npm run build
```

Después de revisar la identidad actual del backend, autorizar y publicar primero
el backend:

```powershell
Set-Location "C:\Users\USER\Documents\tesis\OLI FRESH ICE\OLITRACK\backend"
gcloud run deploy oli-track-v-1-0-0-1-git `
  --source . `
  --project olitrack-f0fcc `
  --region us-central1

gcloud run services update oli-track-v-1-0-0-1-git `
  --project olitrack-f0fcc `
  --region us-central1 `
  --update-env-vars "OSRM_BASE_URL=https://osrm-ayacucho-test-nbnprskwlq-uc.a.run.app,OSRM_IAM_ENABLED=true"
```

Confirma que el despliegue conserve la cuenta de ejecución de Firebase y que esta
tenga `roles/run.invoker` únicamente en el servicio OSRM. Si Cloud Run todavía
rechaza las llamadas al backend con 403 antes de llegar a Java, y después de
autorizar expresamente el acceso público al **backend original**, el comando
independiente es:

```powershell
gcloud run services update oli-track-v-1-0-0-1-git `
  --project olitrack-f0fcc `
  --region us-central1 `
  --no-invoker-iam-check
```

Ese permiso es para el backend OLITRACK, no para OSRM; la autenticación Firebase
y las reglas Spring por rol siguen siendo obligatorias. Después de verificar la
API, publicar el frontend de producción:

```powershell
Set-Location "C:\Users\USER\Documents\tesis\OLI FRESH ICE\OLITRACK\frontend"
npm run build
firebase deploy --only hosting --project olitrack-f0fcc
```

El cambio de IAM, el despliegue de Cloud Run y `firebase deploy` quedan sujetos a
autorización explícita. Ninguno se ejecutó durante esta integración.
