# S Pen Lab · Beta de tinta 0.1.1

Prueba de escritura y Air Actions para el Galaxy S24 Ultra. Esta beta se instala junto a la versión original y conserva los trazos en memoria durante la sesión. Consulta [PLAN.md](PLAN.md) para la prueba comparativa y el orden de mejoras.

## Prueba en el teléfono

1. Instala la APK de depuración y abre **S Pen Lab Beta**. También puedes instalar desde el proyecto con **Run** en Android Studio.
2. Acerca la punta a la cuadrícula y comprueba que aparece un cursor del color seleccionado. Escribe con poca y mucha presión. Cambia el ángulo del S Pen y observa los grados en el panel superior.
3. Escribe con la palma apoyada. La hoja debe quedarse quieta y ningún dedo debe dejar tinta. El contador «Táctil» indica los contactos que llegan al lienzo y «Cancelados» indica los eventos de cancelación que Android entrega. Que el contador esté en cero es posible si el sistema filtra esos contactos antes de entregarlos a la app.
4. Con el lápiz cerca de la pantalla, mantén presionado el botón antes de tocar un trazo y pásalo por encima. Suelta para volver al bolígrafo en el siguiente trazo. La barra superior debe mostrar el estado del botón cuando el teléfono lo reporta.
5. Activa **Ajustes → Funciones avanzadas → S Pen → Acciones aéreas**. Si **S Pen Lab Beta** aparece entre las acciones de aplicaciones, actívala y verifica sus asignaciones. Con el editor abierto y el lápiz fuera del alcance del cursor, haz un clic para deshacer. Para cambiar el color, mantén el botón, desplaza el lápiz hacia la derecha en el aire y suelta. Repite ambos comandos y verifica el contador y la última acción al pie de la hoja. Si la app no aparece o el contador no cambia, registra tu versión de One UI y el paso que falló.
6. Escribe algo rápido en Samsung Notes y luego aquí, con las mismas opciones de pantalla. Compara qué tanto se separa la tinta de la punta en curvas y cambios de dirección. Esta prueba es visual y no mide milisegundos.

## Compilación

Abre esta carpeta como proyecto en Android Studio. Usa un JDK 17 o 21 y el SDK de Android 36. En Windows puedes generar la APK de depuración con:

```bat
gradlew.bat :app:assembleDebug
```

En Linux o macOS:

```bash
./gradlew :app:assembleDebug
```

La APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Para instalar el archivo entregado mediante ADB, ejecuta `adb install SPenLab_Tinta_Beta.apk` desde una terminal en la carpeta que contiene el APK. El paquete de depuración es `mx.spen.lab.preview`.

Compose contiene la interfaz. La captura de presión, inclinación, proximidad y botón usa `MotionEvent` en el lienzo nativo. Jetpack Ink renderiza el trazo activo con predicción y transfiere los trazos terminados a una segunda capa. El borrador solo afecta trazos completos. Samsung administra el enlace Bluetooth y envía eventos según las asignaciones declaradas en `res/xml/remote_actions.xml`. Con una app en primer plano, F9 y F10 también permiten verificar por ADB el despacho de comandos. Eso no comprueba Bluetooth:

```bash
adb shell input keyevent KEYCODE_F9
adb shell input keyevent KEYCODE_F10
```

[Documentación de Air Actions](https://developer.samsung.com/galaxy-spen-remote/air-actions.html) · [Jetpack Ink](https://developer.android.com/jetpack/androidx/releases/ink)
