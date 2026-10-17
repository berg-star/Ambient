package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal interface QWeatherApi {
    @GET("v7/minutely/5m")
    suspend fun minutely(@Query("location") location: String, @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("weather/v1/hourly/{latitude}/{longitude}")
    suspend fun hourly(@Path("latitude") latitude: String, @Path("longitude") longitude: String,
                       @Query("hours") hours: Int = 72, @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("weather/v1/current/{latitude}/{longitude}")
    suspend fun weather(@Path("latitude") latitude: String, @Path("longitude") longitude: String,
                        @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("airquality/v1/current/{latitude}/{longitude}")
    suspend fun air(@Path("latitude") latitude: String, @Path("longitude") longitude: String,
                    @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("geo/v2/city/lookup")
    suspend fun cities(@Query("location") query: String, @Query("lang") lang: String = "zh",
                       @Query("number") number: Int = 10): Response<JsonObject>
    @GET("v7/astronomy/sun")
    suspend fun sun(@Query("location") location: String, @Query("date") date: String): Response<JsonObject>
    @GET("v7/astronomy/moon")
    suspend fun moon(@Query("location") location: String, @Query("date") date: String,
                     @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("v7/astronomy/solar-elevation-angle")
    suspend fun solar(@Query("location") location: String, @Query("date") date: String,
                      @Query("time") time: String, @Query("tz") zone: String,
                      @Query("alt") referenceAltitude: Int = 0): Response<JsonObject>
}

class QWeatherClient private constructor(private val api: QWeatherApi) {
    constructor(credentials: QWeatherCredentials) : this(createApi(credentials))

    internal constructor(baseUrl: String, client: OkHttpClient) : this(Retrofit.Builder()
        .baseUrl(baseUrl).client(client).addConverterFactory(GsonConverterFactory.create())
        .build().create(QWeatherApi::class.java))

    suspend fun minutely(area: EnvironmentArea): JsonObject = request { api.minutely(location(area)) }
    suspend fun hourly(area: EnvironmentArea): JsonObject = request {
        api.hourly(coordinate(area.latitude), coordinate(area.longitude))
    }

    suspend fun weather(area: EnvironmentArea): JsonObject = request {
        api.weather(coordinate(area.latitude), coordinate(area.longitude))
    }
    suspend fun air(area: EnvironmentArea): JsonObject = request {
        api.air(coordinate(area.latitude), coordinate(area.longitude))
    }
    suspend fun sun(area: EnvironmentArea, date: String): JsonObject = request { api.sun(location(area), date) }
    suspend fun moon(area: EnvironmentArea, date: String): JsonObject = request { api.moon(location(area), date) }
    suspend fun solar(area: EnvironmentArea, date: String, time: String, zone: String, altitude: Int = 0): JsonObject =
        request { api.solar(location(area), date, time, zone, altitude) }
    private fun location(area: EnvironmentArea) = "${coordinate(area.longitude)},${coordinate(area.latitude)}"
    suspend fun cities(query: String): List<EnvironmentArea> {
        val json = request { api.cities(query) }
        if (json.string("code") == "404") return emptyList()
        if (json.string("code") != "200") throw EnvironmentException("城市查询失败，请检查 GeoAPI 权限后重试。")
        return json.array("location").mapNotNull { element ->
            val city = element.asJsonObject
            val lat = city.number("lat") ?: return@mapNotNull null
            val lon = city.number("lon") ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val label = listOf(city.string("adm1"), city.string("adm2"), city.string("name"))
                .filter { it.isNotBlank() }.distinct().joinToString(" · ")
            EnvironmentArea(label.ifBlank { "所选区域" }, lat, lon,
                timeZone = city.string("tz").takeIf { runCatching { java.time.ZoneId.of(it) }.isSuccess })
        }
    }

    private suspend fun request(block: suspend () -> Response<JsonObject>): JsonObject {
        try {
            val response = block()
            if (!response.isSuccessful) {
                // Only a documented problem type is used. Never expose the raw error body/details.
                val problemType = runCatching {
                    response.errorBody()?.charStream()?.use { reader ->
                        val buffer = CharArray(4096)
                        val count = reader.read(buffer)
                        if (count <= 0) null else JsonParser.parseString(String(buffer, 0, count))
                            .asJsonObject.obj("error").string("type")
                    }
                }.getOrNull()
                throw EnvironmentException(httpError(response.code(), problemType))
            }
            return response.body() ?: throw EnvironmentException("服务返回空数据，请稍后重试。")
        } catch (e: CancellationException) { throw e }
        catch (e: EnvironmentException) { throw e }
        catch (_: IOException) { throw EnvironmentException("网络连接失败，请检查网络后重试。") }
        catch (_: Exception) { throw EnvironmentException("服务数据格式异常，请稍后重试。") }
    }

    companion object {
        fun coordinate(value: Double): String = String.format(Locale.US, "%.2f", value)
        fun httpError(code: Int, problemType: String? = null): String = when (code) {
            401 -> "和风认证失败（401），请检查凭据、API Host 和手机日期时间。"
            402 -> "服务余额或额度不足（402），请查看和风控制台。"
            403 -> when (problemType) {
                "https://dev.qweather.com/docs/resource/error-code/#security-restriction" ->
                    "和风请求限制（403），请检查当前 JWT 凭据的 API 授权及 IP、域名或应用限制。"
                "https://dev.qweather.com/docs/resource/error-code/#no-credit" -> "和风可用额度不足（403），请检查控制台额度。"
                "https://dev.qweather.com/docs/resource/error-code/#overdue" -> "和风存在逾期账单（403），请检查控制台账单。"
                "https://dev.qweather.com/docs/resource/error-code/#invalid-host" -> "和风 API Host 不正确（403），请核对控制台专属 Host。"
                "https://dev.qweather.com/docs/resource/error-code/#deprecated" -> "当前和风接口已停用（403），需要更新接口版本。"
                else -> "接口访问被拒绝（403），请检查项目的 API 授权及应用限制。"
            }
            404 -> "该区域或接口暂无数据（404）。"
            429 -> "请求过于频繁（429），请稍后重试。"
            in 500..599 -> "和风服务暂时不可用（$code），请稍后重试。"
            else -> "区域数据请求失败（$code），请稍后重试。"
        }
        private fun createApi(credentials: QWeatherCredentials): QWeatherApi {
            credentials.validate()
            val jwt = QWeatherJwt(credentials)
            val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    // A redirect or unexpected host must never receive a signed token.
                    check(chain.request().url.isHttps && chain.request().url.host == credentials.apiHost)
                    chain.proceed(chain.request().newBuilder()
                        .header("Authorization", "Bearer ${jwt.create()}").build())
                }.build()
            return Retrofit.Builder().baseUrl("https://${credentials.apiHost}/").client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(QWeatherApi::class.java)
        }
    }
}

internal fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.number(key: String): Double? = string(key).toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
internal fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
