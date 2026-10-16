package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.google.gson.JsonObject
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
    @GET("weather/v1/current/{latitude}/{longitude}")
    suspend fun weather(@Path("latitude") latitude: String, @Path("longitude") longitude: String,
                        @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("airquality/v1/current/{latitude}/{longitude}")
    suspend fun air(@Path("latitude") latitude: String, @Path("longitude") longitude: String,
                    @Query("lang") lang: String = "zh"): Response<JsonObject>
    @GET("geo/v2/city/lookup")
    suspend fun cities(@Query("location") query: String, @Query("lang") lang: String = "zh",
                       @Query("number") number: Int = 10): Response<JsonObject>
}

class QWeatherClient private constructor(private val api: QWeatherApi) {
    constructor(credentials: QWeatherCredentials) : this(createApi(credentials))

    internal constructor(baseUrl: String, client: OkHttpClient) : this(Retrofit.Builder()
        .baseUrl(baseUrl).client(client).addConverterFactory(GsonConverterFactory.create())
        .build().create(QWeatherApi::class.java))

    suspend fun weather(area: EnvironmentArea): JsonObject = request {
        api.weather(coordinate(area.latitude), coordinate(area.longitude))
    }
    suspend fun air(area: EnvironmentArea): JsonObject = request {
        api.air(coordinate(area.latitude), coordinate(area.longitude))
    }
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
            EnvironmentArea(label.ifBlank { "所选区域" }, lat, lon)
        }
    }

    private suspend fun request(block: suspend () -> Response<JsonObject>): JsonObject {
        try {
            val response = block()
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                throw EnvironmentException(httpError(response.code()))
            }
            return response.body() ?: throw EnvironmentException("服务返回空数据，请稍后重试。")
        } catch (e: CancellationException) { throw e }
        catch (e: EnvironmentException) { throw e }
        catch (_: IOException) { throw EnvironmentException("网络连接失败，请检查网络后重试。") }
        catch (_: Exception) { throw EnvironmentException("服务数据格式异常，请稍后重试。") }
    }

    companion object {
        fun coordinate(value: Double): String = String.format(Locale.US, "%.2f", value)
        fun httpError(code: Int): String = when (code) {
            401 -> "和风认证失败（401），请检查凭据、API Host 和手机日期时间。"
            402 -> "服务余额或额度不足（402），请查看和风控制台。"
            403 -> "接口访问被拒绝（403），请检查项目的 API 授权及应用限制。"
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
