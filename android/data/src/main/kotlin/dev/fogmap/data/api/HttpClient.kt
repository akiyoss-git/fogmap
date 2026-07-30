package dev.fogmap.data.api

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

/**
 * HTTP-клиент к своему бэкенду.
 *
 * <p>Пиннинг сертификата настраивается здесь. Сейчас список пуст и пиннинг не работает — у проекта
 * ещё нет боевого домена, а прописать выдуманные отпечатки хуже, чем не прописать никаких: сборка
 * будет выглядеть защищённой, оставаясь незащищённой.
 *
 * <p>Перед выкладкой сюда добавляются отпечатки ключа боевого домена и запасного центра
 * сертификации. Взять их можно так:
 *
 * <pre>
 * openssl s_client -servername HOST -connect HOST:443 &lt; /dev/null \
 *   | openssl x509 -pubkey -noout \
 *   | openssl pkey -pubin -outform der \
 *   | openssl dgst -sha256 -binary | base64
 * </pre>
 *
 * <p>Запасной отпечаток обязателен: с одним-единственным плановая смена сертификата превращается в
 * отказ обслуживания для всех, кто не успел обновить приложение.
 */
internal object HttpClient {

    /** Пары «хост — отпечаток вида sha256/...». Пусто — пиннинга нет. */
    private val PINS: List<Pair<String, String>> = emptyList()

    fun build(): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (PINS.isNotEmpty()) {
            val pinner = CertificatePinner.Builder()
            PINS.forEach { (host, pin) -> pinner.add(host, pin) }
            builder.certificatePinner(pinner.build())
        }
        return builder.build()
    }
}
