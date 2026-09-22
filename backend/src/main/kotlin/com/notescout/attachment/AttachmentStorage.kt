package com.notescout.attachment

import com.notescout.config.AppProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Где лежат файлы вложений.
 *
 * Интерфейс отдельный, потому что мест хранения как минимум два: том на
 * сервере (сейчас) и S3-совместимое хранилище (когда понадобится). Переезд
 * не должен трогать ни схему, ни API — только реализацию.
 */
interface AttachmentStorage {
    fun store(key: String, content: InputStream)
    fun load(key: String): InputStream
    fun delete(key: String)

    /** Ключ для нового файла. Формируется здесь, чтобы хранилище само решало,
     *  как раскладывать файлы, и чтобы внешний код не мог подсунуть путь. */
    fun newKey(userId: UUID): String
}

/**
 * Файлы в каталоге на диске.
 *
 * Раскладка — по пользователю и идентификатору вложения. Имя, которое ввёл
 * пользователь, в путь не попадает вовсе: оно может содержать что угодно,
 * включая последовательности переходов вверх по каталогам.
 */
@Component
class LocalAttachmentStorage(
    private val properties: AppProperties,
) : AttachmentStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    private val root: Path = Path.of(properties.attachments.directory).toAbsolutePath().normalize()

    @PostConstruct
    fun prepare() {
        Files.createDirectories(root)
        log.info("Хранилище вложений: {}", root)
    }

    override fun newKey(userId: UUID): String = "$userId/${UUID.randomUUID()}"

    override fun store(key: String, content: InputStream) {
        val target = pathOf(key)
        Files.createDirectories(target.parent)

        // Пишем во временный файл и переносим: обрыв на середине не оставит
        // в хранилище обрубок, который потом скачается как целый файл.
        val tmp = Files.createTempFile(target.parent, ".upload-", ".part")
        try {
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            Files.deleteIfExists(tmp)
            throw error
        }
    }

    override fun load(key: String): InputStream = Files.newInputStream(pathOf(key))

    override fun delete(key: String) {
        Files.deleteIfExists(pathOf(key))
    }

    private fun pathOf(key: String): Path {
        val resolved = root.resolve(key).normalize()
        // Ключи формируются только из uuid, но проверка не лишняя: хранилище
        // не должно зависеть от аккуратности вызывающего кода.
        check(resolved.startsWith(root)) { "Недопустимый ключ файла" }
        return resolved
    }
}
