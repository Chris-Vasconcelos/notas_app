# S Pen Lab · Plan de mejora

## Beta 0.1.1 · Tinta

La captura del Galaxy S24 Ultra muestra picos y zonas triangulares en varios trazos. Jetpack Ink 1.1.0-alpha04 incluye la primera corrección documentada para deformaciones en trazos terminados en algunos dispositivos tras una actualización del sistema. La beta prueba esa versión con el mismo pincel, predicción y configuración de entrada de la versión 0.1.0. La causa en este Galaxy se confirma comparando ambas apps.

- Instala la beta al lado de S Pen Lab. Tendrá el nombre **S Pen Lab Beta** y su propia entrada en los ajustes de acciones aéreas, si One UI la muestra.
- En cada app dibuja círculos cerrados, diagonales en ambos sentidos, puntos y letras rápidas. Haz un trazo manteniendo la punta quieta al final y luego levántala. Observa si el pico aparece antes o después de levantar el lápiz.
- Comprueba que la presión siga variando el grosor y que el botón lateral siga borrando trazos. Si el barrido persiste mientras escribes, la siguiente prueba comparará predicción activada y desactivada con la misma versión del motor.

[Historial de Jetpack Ink](https://developer.android.com/jetpack/androidx/releases/ink#1.1.0-alpha04)

## Referencias para el editor

| App | Qué hace | Qué tomar para S Pen Lab |
| --- | --- | --- |
| [Samsung Notes](https://www.samsung.com/us/support/answer/ANS10003634/) | Botón del S Pen como borrador temporal y lazo para seleccionar tinta. | Volver automáticamente a la pluma al soltar el botón y ofrecer una herramienta de borrado visible. |
| [Goodnotes para Android](https://support.goodnotes.com/hc/en-us/articles/7378598719759-Get-Started-With-Goodnotes-for-Android-Windows-and-Web) | Barra con pluma, resaltador, borrador, lazo y formas. Tiene plantillas e importación de PDF. | Herramientas compactas y un flujo para abrir, anotar y exportar una exposición. |
| [OneNote para Android](https://support.microsoft.com/en-us/onenote/take-handwritten-notes-in-onenote-for-android) | Barra que se contrae, líneas o cuadrícula y desplazamiento con dos dedos. | Un modo de escritura amplia y navegación táctil que se pueda usar sin pintar con la palma. |
| [MyScript Notes](https://help.myscript.com/notes/edit-content/convert/) | Selección y conversión de escritura, diagramas y ecuaciones. | Priorizar búsqueda manuscrita en español y evaluar ecuaciones como función aparte con ejercicios propios. |

## Orden de entrega

1. **Tinta y controles.** Confirmar que los remates conservan la forma y que el borrador y el rechazo de palma se comportan bien. Verificar si One UI entrega por separado el clic y el gesto remoto. Mantener los botones en pantalla como alternativa a Air Actions.
2. **Editor.** Barra inferior compacta con pluma, borrador, color, grosor y deshacer. Guardar los ajustes de diagnóstico en un menú. Tema grafito, acento azul petróleo y papel claro u oscuro independiente del tema. Probar legibilidad y controles con una sola mano en el S24 Ultra.
3. **Notas locales.** Títulos, autoguardado, carpetas, plantillas blanca, rayada y cuadriculada. Reabrir tras cerrar la app y comprobar que los trazos siguen en su sitio. Después, incorporar resaltador y lazo.
4. **Trabajos de clase.** Importar PDF y exportarlo anotado. Añadir formas simples para circuitos y diagramas. Probar con un ejercicio de Física y un diagrama de Sistemas Digitales.
5. **Búsqueda manuscrita.** Empezar por texto corto en español. Evaluar por separado qué tan bien se pueden reconocer fórmulas.
