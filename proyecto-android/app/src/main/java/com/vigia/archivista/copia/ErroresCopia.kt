package com.vigia.archivista.copia

/** Error con un mensaje ya listo para mostrar a la persona. */
class ErrorCopia(mensaje: String) : Exception(mensaje)

/** La contraseña no abre la copia (o el archivo está dañado: AES-GCM no distingue ambos casos). */
class ContrasenaIncorrecta : Exception("contraseña incorrecta o archivo dañado")

/** El archivo elegido no es una copia de El Archivista, o está incompleto. */
class ArchivoNoValido(mensaje: String) : Exception(mensaje)
