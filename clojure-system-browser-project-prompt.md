# Prompt inicial — Clojure System Browser

> **Uso:** entregar este documento a un agente de desarrollo como especificación inicial para crear el repositorio y una primera implementación funcional. El nombre de trabajo es **Clojure System Browser (CSB)**; puede cambiarse.

## Rol y objetivo

Actúa como arquitecto y desarrollador senior de Clojure/JVM, con experiencia en herramientas para desarrolladores, nREPL, análisis de código fuente, interfaces de escritorio y terminal.

Construye un **System Browser para Clojure inspirado en los navegadores de Smalltalk**, adaptado a la idiosincrasia de Clojure: namespaces, vars, funciones, macros, protocolos, multimétodos, records y datos. **No construyas simplemente un explorador de archivos o un IDE generalista.** La unidad principal de navegación y edición debe ser la **definición**, no el archivo completo.

El producto evolucionará en dos escenarios, implementados en fases separadas:

1. **MVP — Source World (sin REPL):** explorar y editar de manera segura proyectos Clojure en disco, desde Desktop y TUI. No implementar conexiones REPL en esta fase.
2. **Post-MVP — Live World (con REPL):** conectar por nREPL a una JVM en ejecución; explorar namespaces y vars cargados, consultar metadatos y evaluar definiciones. Mostrar claramente las diferencias entre lo que hay en disco y lo que se sabe del runtime, sin inventar equivalencias.

La primera distribución será una **aplicación Desktop**; además, crea una **TUI (terminal user interface)** ligera que comparta exactamente el núcleo y los casos de uso. Diseña una frontera de API estable para futuras integraciones con CIDER/Emacs y VS Code, pero **no implementes esas integraciones en el MVP**. Incorpora desde el diseño una **API de extensiones en Clojure**, independiente de Desktop y TUI. La primera extensión oficial será un **Test Runner integrado inspirado en SUnit**; su implementación llegará después del hito de conexión nREPL. El MVP solo debe validar un registro y una contribución mínima de extensión, sin depender de un REPL.

## Principios no negociables

- **Clojure primero:** el núcleo, el modelo de dominio y los servicios deben escribirse en Clojure; las interfaces solo adaptan presentación e interacción.
- **Núcleo headless:** ni JavaFX ni el toolkit de terminal pueden aparecer como dependencias del dominio o de los adaptadores de fuente/runtime.
- **Fuentes normales:** los archivos `.clj` son la fuente persistente de verdad. No introducir un formato propietario ni exigir anotaciones en el código.
- **Preservación estructural:** editar una definición debe conservar el resto del archivo, incluyendo comentarios, formato, reader conditionals y convenciones de espaciado en la medida soportada. Nunca hacer `read-string` + `pr-str` sobre el archivo completo para reescribirlo.
- **Separación Save/Eval:** en el MVP solo existe Save. En la fase posterior de Live World, evaluar en el REPL será una acción diferente. Un futuro `Accept` podrá coordinarlas, pero deberá informar resultados parciales; no asumir atomicidad entre disco y JVM.
- **Conservadurismo:** no sobrescribir cambios externos silenciosamente, no ejecutar código solo por navegar, y no inventar código fuente original para vars cargadas.
- **Iteraciones pequeñas:** entregar primero un recorrido vertical operativo; no implementar anticipadamente todas las funciones de Smalltalk.
- **Extensibilidad sin acoplamiento:** las extensiones añaden comandos, consultas, navegación y vistas declarativas sin depender de clases JavaFX ni controles específicos de la TUI. No ejecutar plugins desconocidos automáticamente.
- **Portabilidad razonable:** priorizar Linux, macOS y Windows en el diseño; las comprobaciones automatizadas deben poder ejecutarse en CI sin interfaz gráfica.

## Modelo conceptual

Navegación principal:

`System / Project → Namespace → Category → Definition → Source / Runtime / References`

Define entidades y capacidades sin asumir que todas las construcciones corresponden a una sola var:

- **System/Project:** raíces de código, classpath y conexión opcional a runtime.
- **Namespace:** namespace detectado en fuente, en runtime, o en ambos.
- **Category:** agrupación lógica y opcional de definiciones. Inicialmente inferida por tipo (`functions`, `macros`, `values`, `protocols`, `types`, `multimethods`, `tests`, etc.) y por privacidad; permitir más adelante metadatos explícitos y categorías virtuales.
- **Definition:** constructo top-level identificable, con identidad estable tanto como sea posible: archivo, namespace, tipo, nombre/clave, localización y rango de texto. Modelar casos como `defmethod`, `extend-type`, `extend-protocol`, `defprotocol`, `defrecord` y `deftype` sin suponer una relación 1:1 con vars.
- **Source World:** definiciones y referencias extraídas del código fuente.
- **Live World:** namespaces, vars y metadatos observables en una sesión nREPL; capacidades dependientes del runtime conectado.
- **Change:** modificación validada, con hashes y resultado de escritura; base para historial futuro.

En el MVP solo se implementa **Source**. Tras incorporar nREPL, la aplicación podrá ofrecer **Live** y **Combined**. En esa fase, *Combined* debe indicar estados solo cuando exista información suficiente; por ejemplo, `source-only`, `runtime-only`, `present-in-both` o `unknown`. No marcar `in-sync` comparando únicamente nombres. La comparación semántica exacta de definiciones solo se hará cuando exista evidencia registrada, por ejemplo evaluaciones realizadas por el propio navegador.

## Arquitectura y módulos sugeridos

```text
clojure-system-browser/
├── README.md
├── deps.edn
├── modules/
│   ├── browser-model/       # entidades, protocolos/ports y casos de uso
│   ├── source-adapter/      # lectura, análisis, edición segura de .clj
│   ├── runtime-adapter/     # POST-MVP: nREPL, inspección y evaluación
│   ├── browser-service/     # fachada de operaciones y eventos; sin UI
│   └── extension-api/       # registro, capacidades y contribuciones declarativas
├── apps/
│   ├── desktop/             # cljfx / JavaFX
│   └── tui/                 # interfaz de terminal
├── extensions/
│   └── test-runner/        # primera extensión oficial (post-MVP)
├── examples/
│   └── sample-project/
├── test/
└── docs/
    ├── architecture.md
    ├── decisions/
    └── roadmap.md
```

La estructura es orientativa: simplifícala si un monorepo multimódulo dificulta innecesariamente el arranque. Prefiere límites claros de namespaces y dependencias a una estructura ceremoniosa.

### Dependencias candidatas (verificar antes de fijar versiones)

- **rewrite-clj:** parsear y reescribir preservando representación textual.
- **clj-kondo / análisis exportado:** índice semántico y referencias, cuando proceda; dejar búsquedas avanzadas para fases posteriores.
- **nREPL (post-MVP):** conexión, consulta de capacidades, evaluación e introspección básica. **No exigir cider-nrepl**; aprovecharlo opcionalmente en el futuro. No incluir esta dependencia en el MVP.
- **cljfx + JavaFX:** primera interfaz de escritorio.
- **TUI:** evaluar una biblioteca JVM/Clojure mantenida, con soporte real para teclas, redibujado y resize (por ejemplo Lanterna vía interop o equivalente). Elegir tras una *spike* breve y documentar por qué; evitar implementar un motor de terminal desde cero.

No asumir APIs ni versiones exactas sin verificarlas. Registrar decisiones de tecnología en ADRs cortos.

## Contrato del núcleo (boceto, no API cerrada)

El core debe exponer operaciones desacopladas de cualquier UI, por ejemplo:

```clojure
(list-namespaces system {:world :source})
(list-definitions system 'shop.orders {:world :source})
(list-categories system 'shop.orders)
(get-definition-source system definition-id)
(save-definition! system definition-id new-source {:expected-revision revision})
;; Solo post-MVP: Live World y conexión nREPL
(connect-runtime! system {:host "127.0.0.1" :port 7888})
(runtime-capabilities system)
(list-namespaces system {:world :live})
(inspect-var system 'shop.orders/find-order)
(eval-definition! system definition-id {:source edited-form})
(disconnect-runtime! system)
```

Durante el MVP implementar únicamente operaciones Source World y Save; las firmas runtime son objetivos de diseño, no código obligatorio ni APIs simuladas.

Los errores deben ser datos estructurados (`:type`, `:message`, `:details`) y los resultados de operaciones que cambian estado deben distinguir explícitamente éxito, conflicto, error de parseo, error de E/S y error de evaluación.

No es obligatorio copiar estas firmas: especifica y prueba el contrato elegido. Evita que la UI acceda directamente a detalles de `rewrite-clj`, sockets nREPL o rutas internas.

## API de extensiones Clojure (contrato arquitectónico del MVP)

El proyecto debe tratar las extensiones como clientes del **Browser Service**, no como código que modifica directamente el estado de las interfaces. La API debe ser pequeña, versionada y probada; evitar diseñar un framework de plugins genérico antes de necesitarlo.

**Contribuciones previstas:**

- **Comandos** identificados por keywords namespaced, con descripción, argumentos/resultados como datos y requisitos de capacidad (por ejemplo, `:runtime/eval`).
- **Vistas** declaradas con identificador, título y proveedor de datos; Desktop y TUI eligen cómo representarlas. Una extensión no puede asumir píxeles, widgets JavaFX ni teclas específicas.
- **Navegación** entre entidades mediante IDs estables de namespace, definición, test, ubicación en fuente, ejecución y resultado.
- **Categorías y proveedores de datos** opcionales, sin requerir anotaciones en el código fuente.
- **Eventos** de ciclo de vida y de resultado; suscriptores pueden escuchar, pero no bloquear la UI ni invocar evaluación sin una acción explícita/autorizada.

Ejemplo **ilustrativo, no API existente**:

```clojure
{:id :csb.ext/test-runner
 :api-version 1
 :requires #{:source/read :runtime/eval}
 :contributions
 {:commands [:tests/run-one :tests/run-namespace :tests/run-all]
  :views    [:tests/tree :tests/results]
  :navigation [:tests/open-source :tests/inspect-failure]}}
```

Definir un `ExtensionContext` con acceso explícito y limitado a servicios permitidos; registrar/desregistrar contribuciones, detectar colisiones de IDs, informar incompatibilidades de versión y degradar funcionalidades cuando falta una conexión REPL. Las extensiones se cargan **por configuración explícita**, desde código/dependencias de confianza del usuario: Clojure en la misma JVM **no es un sandbox de seguridad**. Documentar el alcance y el riesgo de ejecutar extensiones de terceros.

En el MVP, implementar y probar **solo el registro, el listado de contribuciones, el despacho de un comando ficticio sin efectos y una vista de datos mínima** desde Desktop y TUI. La contribución de ejemplo no debe exigir `:runtime/eval` ni ninguna conexión REPL. No es necesario soportar instalación dinámica, hot reload de plugins, aislamiento de procesos ni un catálogo de extensiones. La API deberá poder evolucionar con versión.

## Primera extensión oficial (post-MVP): Test Runner estilo SUnit

Construir `extensions/test-runner` como una extensión normal, **sin privilegios ocultos ni dependencias de JavaFX o la TUI**. Debe sentirse integrada en el navegador: descubrir tests, navegar hasta ellos, ejecutarlos, mostrar resultados y volver rápidamente al código que falla. La ejecución se realiza sobre el **Live World** mediante REPL, disponible desde el Hito 5; sin conexión REPL se permite descubrir tests estáticamente, pero **no fingir ejecuciones**.

**Proveedores:** comenzar con `clojure.test`. Diseñar una interfaz interna `TestProvider` para añadir posteriormente un adaptador Kaocha, sin comprometerse con sus APIs antes de verificarlas. Para ClojureScript u otros runners, declarar explícitamente soporte futuro.

**Operaciones de usuario:** ejecutar un test, un namespace de tests o una suite/proyecto según los tests descubiertos; relanzar fallidos; cancelar una ejecución si el backend lo soporta; abrir el origen de un test o de una aserción fallida. La ejecución de suites completas debe requerir una acción explícita y advertir que los tests pueden tener efectos secundarios.

**Modelo de datos:** IDs de `test-run`, `test-case` y `assertion`; estado `:queued/:running/:passed/:failed/:error/:cancelled` cuando sea observable; contadores, duración cuando pueda medirse, ubicación fuente opcional, fallo/exception normalizados y eventos con identidad de ejecución y orden. Diferenciar fallo de aserción (`:fail`) de error de ejecución (`:error`); no derivar el resultado únicamente de texto `stdout`.

**Integración técnica:** usar las capacidades reales de `clojure.test` y/o middleware de test disponible en la conexión. Preservar fixtures y semántica del runner; correlacionar eventos asíncronos con una sola ejecución; manejar timeout, desconexión, ejecución concurrente y cancelación de forma conservadora. Si para captura estructurada se necesita código auxiliar en el proceso remoto, cargarlo **solo con permiso explícito**, documentar su alcance y evitar alterar permanentemente el reporter global de `clojure.test`. No confiar en `read-string` de datos de red desconocidos.

**Desktop:** incorporar una vista `Tests` con árbol namespace → test, estado por prueba, resumen passed/failed/error, filtros, panel de detalles y enlaces a código. **TUI:** ofrecer las mismas operaciones y datos con navegación por teclado y un panel de resultados, aunque su presentación sea más sencilla. Ambas consumen las mismas operaciones de la extensión.

**Inspector de fallos:** mostrar `expected`, `actual` y detalles de excepción como datos renderizables cuando puedan capturarse; truncar valores grandes, indicar cuando solo existe representación textual y no retener referencias de objetos remotos sin control. La inspección profunda de valores vivos es una mejora posterior, integrada con el futuro Inspector general.

**Casos de aceptación de la extensión:**

1. Descubrir tests en un proyecto ejemplo y abrir su definición desde Desktop y TUI.
2. Ejecutar un test y un namespace conectado a un REPL, obteniendo resultados estructurados y un ID de ejecución.
3. Visualizar al menos un test exitoso, un fallo de aserción y una excepción, distinguiéndolos correctamente.
4. Respetar fixtures `clojure.test` en las ejecuciones soportadas, con tests que demuestren el comportamiento.
5. Navegar desde el fallo a la definición de test; si existe ubicación de aserción, abrirla.
6. Ante desconexión o timeout, informar estado parcial sin reportar falsos éxitos.
7. Demostrar que el módulo `test-runner` no depende de JavaFX ni del toolkit TUI y que ambos clientes reutilizan sus comandos.

## Experiencia Desktop (MVP — sin REPL)

Construir una ventana con la inspiración de un browser Smalltalk:

1. Panel de **namespaces**.
2. Panel de **categorías**.
3. Panel de **definiciones**.
4. Panel principal para **código fuente de la definición seleccionada**.
5. Barra de estado con proyecto, archivo seleccionado, cambios pendientes y errores de fuente. El estado del REPL se añadirá post-MVP.

Acciones mínimas: abrir proyecto, seleccionar definición, editar, **Save**, descartar o recargar cambios y mostrar errores/conflictos. **No implementar conexión, evaluación ni controles REPL en el MVP.**

Atajos razonables y coherentes: búsqueda incremental local, navegación por teclado y `Ctrl+S` para guardar. Añadir un atajo diferenciado para evaluar solo en la fase Live World. El editor debe manejar texto multilinea; se puede comenzar con un control estándar si documentamos sus límites (sin exigir inicialmente un editor de código completo).

## Experiencia TUI (MVP — sin REPL)

La TUI es un **cliente de primera clase del mismo servicio**, pero deliberadamente más pequeña. No debe bloquear el lanzamiento de Desktop por tareas de estilizado.

Vista propuesta: tres listas navegables (namespace → categoría → definición) y un panel de solo lectura o edición simple de la definición. Debe soportar teclado, resize y salida limpia.

Operaciones mínimas:

- Abrir proyecto por argumento CLI: `csb tui --project /ruta/al/proyecto` (sintaxis definitiva a decidir).
- Navegar por namespaces, categorías y definiciones.
- Ver el texto de una definición.
- Guardar mediante el mismo servicio seguro del núcleo, **solo si el widget de edición TUI resulta fiable**. En caso contrario, primera TUI de solo lectura y comando “abrir en `$EDITOR`”, con recarga y validación; implementar después edición integrada.

Usar una ayuda de teclas visible (`?`), feedback de errores y un comando de salida (`q`). La TUI no debe cargar JavaFX en su classpath/runtime.

## Source Adapter: edición segura

En el primer MVP, acotar las escrituras a **formas top-level únicas en `.clj`**, inicialmente `defn` y `defn-`. Detectar y explicar los casos no soportados en vez de modificar el archivo incorrectamente.

Flujo al guardar:

1. Leer bytes y registrar revisión/hash del archivo cuando se presenta la definición.
2. Identificar la forma por identidad y localización estable dentro de una versión dada.
3. Sustituir únicamente esa forma con `rewrite-clj` o una estrategia estructural equivalente que preserve el resto del contenido.
4. Validar la estructura sintáctica del documento completo antes de escribir. Documentar límites de análisis de reader forms y de semántica Clojure.
5. Releer/verificar revisión; si cambió el archivo, **rechazar con conflicto** y ofrecer recarga, sin reemplazo silencioso.
6. Escribir a archivo temporal en el mismo directorio y realizar reemplazo atómico donde lo soporte el sistema; gestionar errores y limpieza. No afirmar durabilidad ante crash sin implementarla.
7. Reindexar la definición y emitir evento de cambio.

El soporte para `.cljc`, condicionales de lector complejos y construcciones como `defmethod` llegará después de validar los primeros casos; deben poder leerse cuando sea seguro, aunque no editarse todavía.

## Runtime Adapter: REPL vivo, pero controlado (solo post-MVP)

**Este apartado es una especificación para una fase posterior. No implementar el adaptador, la conexión, la introspección ni la evaluación en el MVP.**

- Conectarse a un nREPL **ya iniciado**; no ejecutar automáticamente proyectos ajenos ni iniciar JVMs en esa primera fase de Live World.
- Soportar localhost por defecto y host/puerto configurables. Advertir que nREPL otorga capacidad de ejecución de código; no exponerlo públicamente sin protección o túnel seguro.
- Descubrir operaciones disponibles mediante `describe`; funcionar con nREPL básico.
- Permitir listar namespaces y obtener metadatos de vars públicas y privadas cuando sea viable, distinguiendo errores de permisos/capacidad de ausencia de datos.
- Evaluar **solo tras acción explícita** del usuario y presentar `value`, `out`, `err` y excepciones de forma discernible.
- Manejar timeouts, desconexiones y resultados asincrónicos sin bloquear UI, así como sesiones nREPL adecuadamente separadas.
- No presuponer que las vars preservan su texto fuente exacto ni que la redefinición actualiza cierres o valores previamente materializados.

## Diseño para futuras integraciones y extensiones avanzadas (no implementarlas aún)

El servicio debe permitir más adelante:

- **CIDER/Emacs:** abrir la definición actual en el navegador y navegar de vuelta al buffer/archivo.
- **VS Code/Calva:** panel estructural y selección bidireccional aprovechando APIs públicas cuando estén disponibles.
- **Inspectors:** navegación de mapas, vectores, secuencias, atoms, records y objetos Java.
- **Senders/Implementors:** índices estáticos y, cuando sea posible, información del entorno vivo.
- **Workspaces:** evaluación de expresiones temporales en una sesión dedicada.
- **Change Browser:** historial estructural con snapshots y deshacer seguro.
- **Extensiones adicionales:** inspectores especializados (atoms, Integrant, componentes), proveedores de referencias y adaptadores de otros frameworks de test. El Test Runner estilo SUnit tiene su propia especificación anterior y es el primer candidato oficial.

No inventar un protocolo distribuido complejo ahora. Un contrato interno bien definido es suficiente; valorar EDN sobre stdio/socket solo cuando exista el primer cliente externo real.

## Plan de ejecución por hitos

### Hito 0 — Preparación

- Crear `deps.edn`, convenciones de namespaces, pruebas automatizadas, proyecto de ejemplo y README reproducible.
- Seleccionar biblioteca TUI tras una prueba mínima; registrar elección y limitaciones.
- Definir el contrato del núcleo y estados de carga/error.

### Hito 1 — Core + Source World

- Descubrir namespaces y listar `defn` / `defn-` en `.clj`.
- Extraer correctamente el texto de cada forma y mostrar localización.
- Implementar categorías inferidas y tests con comentarios, cadenas multilinea y cambios de línea.
- Añadir guardado estructural seguro con detección de conflicto.

### Hito 2 — Desktop funcional

- Cuatro paneles Smalltalk-like, navegación, edición por definición y Save.
- Manejo visible de errores y de modificaciones sin guardar.

### Hito 3 — TUI funcional

- Navegación, vista de definición, ayuda de teclado y salida limpia.
- Compartir casos de uso sin duplicar parser, índices ni lógica de escritura.
- Integrar `$EDITOR` si la edición en terminal es todavía poco fiable.

### Hito 4 — Pulido y extensibilidad mínima (cierre del MVP)

- Completar documentación y demostración reproducible **sin REPL**, tanto para Desktop como para TUI.
- Pruebas de regresión para conflictos, fallos de parseo, guardado y cambios externos.
- Implementar el registro versionado de extensiones, un comando de ejemplo sin efectos y una vista de datos mínima observable en Desktop y TUI; verificar que ambas usan la misma API.
- Comprobar que el núcleo, el Source Adapter y la API de extensiones no dependen de JavaFX, TUI ni nREPL.
- Registrar el roadmap de Live World, Test Runner, inspectores y referencias avanzadas.

**El MVP termina aquí. No se exige ni se simula una conexión REPL.**

### Hito 5 — Live World / nREPL (post-MVP)

- Implementar el Runtime Adapter separado, sin contaminar el core ni el flujo Source-only.
- Conexión/desconexión nREPL, detección de capacidades, listado de namespaces/vars y metadatos básicos.
- Evaluación explícita desde Desktop y TUI, con salida, valor y errores distinguibles; control de desconexiones, timeouts y operaciones asíncronas.
- Añadir perspectivas Live y Combined con indicadores conservadores, sin prometer sincronización exacta.
- Probar la ejecución contra un proceso de ejemplo independiente y documentar seguridad y límites.

### Hito 6 — Primera extensión oficial: SUnit-like Test Runner (post-nREPL)

- Implementar el `TestProvider` inicial basado en `clojure.test`, con ejecución en runtime conectado y resultados/eventos estructurados.
- Integrar descubrimiento, ejecución por test/namespace y vista de resultados en Desktop y TUI a través de la extension API.
- Incorporar navegación de fallos a fuente y pruebas de fixtures, errores, desconexiones y correlación de ejecuciones.
- Mantener Kaocha, test watch, inspección profunda de valores y ClojureScript fuera de este primer alcance, salvo que una prueba técnica justifique anticiparlos.

Si el tiempo es limitado, entregar un recorrido vertical antes que una implementación amplia pero incompleta; **la TUI no debe ser una segunda arquitectura**.

## Criterios de aceptación del MVP (estrictamente Source World)

El proyecto solo se considera MVP cuando se pueda demostrar lo siguiente, **sin iniciar ni conectar ningún REPL**:

1. Un proyecto Clojure de ejemplo puede abrirse desde disco sin REPL.
2. Desktop y TUI muestran los mismos namespaces y definiciones de acuerdo con el mismo core.
3. Seleccionar una `defn` presenta **solo esa forma**, no todo el archivo.
4. Editar una `defn` en Desktop y guardarla no altera comentarios ni otras definiciones del archivo.
5. Un cambio externo concurrente provoca un **conflicto visible**, sin sobrescritura.
6. Un error de sintaxis impide guardar una versión inválida.
7. La TUI puede abrir el proyecto, navegar y ver definiciones; permite editar mediante `$EDITOR` o editor propio sin duplicar la lógica de guardado seguro.
8. El núcleo y sus pruebas funcionan de forma headless; la TUI no tiene dependencias JavaFX.
9. Los errores de lectura, parseo y escritura no destruyen cambios locales no guardados.
10. El README documenta instalación, ejecución de Desktop y TUI, limitaciones y una demostración Source-only de inicio a fin.
11. La API mínima de extensiones registra una extensión de ejemplo con comando sin efectos y vista de datos, independientes de UI y REPL, visibles desde Desktop y TUI.
12. Ni el código ni la documentación del MVP presentan conexión nREPL, evaluación o Test Runner como funcionalidades ya implementadas.

**Criterios posteriores:** la conexión y evaluación real se validarán en el Hito 5; el Test Runner, en el Hito 6 y mediante sus criterios específicos.

## Lo que NO hay que hacer todavía

- IDE completo, depurador completo, refactorizaciones globales o editor con todas las funciones de CIDER.
- Compatibilidad perfecta con `.cljs`, `.cljc` y todas las macros/reader conditionals en la edición inicial.
- Persistencia propietaria del proyecto ni modificación automática de `deps.edn`.
- Historial transaccional/undo avanzado sin una especificación de recuperación.
- Implementar conexión nREPL, evaluación de código, Live/Combined World o un falso runtime durante el MVP.
- Arrancar y manipular arbitrariamente REPLs remotos.
- Crear integraciones CIDER o VS Code antes de validar el core y los dos clientes iniciales.
- Construir un marketplace de extensiones, ejecutar plugins no confiables automáticamente o prometer aislamiento de seguridad dentro de la misma JVM.
- Implementar todo el Test Runner antes de completar el núcleo y el registro mínimo de extensiones.

## Instrucciones de entrega para el agente

1. **Empieza verificando** herramientas, bibliotecas y versiones disponibles; documenta elecciones, no inventes APIs.
2. Presenta un plan técnico breve y ejecuta los hitos en orden, manteniendo el proyecto ejecutable.
3. Incluye pruebas automatizadas del core y del adaptador de fuente; no confundas tests de UI con pruebas de lógica.
4. Tras cada hito, informa qué funciona realmente, cómo ejecutarlo y qué queda pendiente.
5. Prioriza nombres claros, funciones pequeñas, datos EDN y límites de efectos secundarios.
6. Si una característica resulta demasiado costosa (por ejemplo, edición TUI avanzada), elige el fallback descrito, documenta la decisión y no sacrifiques la calidad del núcleo.
7. No afirmes que una función está implementada sin código verificable ni pruebas o una demostración reproducible.
8. El entregable final debe incluir código ejecutable, README, arquitectura, decisiones y una lista precisa de limitaciones.

**Secuencia posterior al MVP:** implementar primero el Live World y nREPL (Hito 5). Después desarrollar el Test Runner inspirado en SUnit (Hito 6), manteniendo una sola lógica para Desktop y TUI.

**Objetivo de producto a largo plazo:** conseguir que la exploración, edición y evaluación de un sistema Clojure se sienta tan natural e inmediata como trabajar en un navegador Smalltalk, conservando las fortalezas del ecosistema Clojure: texto fuente, Git, REPL, datos e interoperabilidad.
