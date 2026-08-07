package com.example.myapplication3.network.di

import com.example.myapplication3.network.BuildConfig
import com.example.myapplication3.network.api.alphavantage.AlphaVantageApiService
import com.example.myapplication3.network.api.anthropic.AnthropicApiService
import com.example.myapplication3.network.api.finnhub.FinnhubApiService
import com.example.myapplication3.network.api.openai.OpenAiApiService
import com.example.myapplication3.network.api.nse.NseApiService
import com.example.myapplication3.network.api.polygon.PolygonApiService
import com.example.myapplication3.network.interceptor.RetryInterceptor
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import com.example.myapplication3.core.common.Constants

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().setLenient().create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("polygon")
    fun providePolygonRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.POLYGON_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    @Named("finnhub")
    fun provideFinnhubRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.FINNHUB_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    @Named("alphavantage")
    fun provideAlphaVantageRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.ALPHAVANTAGE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    @Named("openai")
    fun provideOpenAiRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.OPENAI_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    @Named("anthropic")
    fun provideAnthropicRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(Constants.ANTHROPIC_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun providePolygonApiService(@Named("polygon") retrofit: Retrofit): PolygonApiService =
        retrofit.create(PolygonApiService::class.java)

    @Provides
    @Singleton
    fun provideFinnhubApiService(@Named("finnhub") retrofit: Retrofit): FinnhubApiService =
        retrofit.create(FinnhubApiService::class.java)

    @Provides
    @Singleton
    fun provideAlphaVantageApiService(@Named("alphavantage") retrofit: Retrofit): AlphaVantageApiService =
        retrofit.create(AlphaVantageApiService::class.java)

    @Provides
    @Singleton
    fun provideOpenAiApiService(@Named("openai") retrofit: Retrofit): OpenAiApiService =
        retrofit.create(OpenAiApiService::class.java)

    @Provides
    @Singleton
    fun provideAnthropicApiService(@Named("anthropic") retrofit: Retrofit): AnthropicApiService =
        retrofit.create(AnthropicApiService::class.java)

    @Provides
    @Singleton
    @Named("polygonApiKey")
    fun providePolygonApiKey(): String = BuildConfig.POLYGON_API_KEY

    @Provides
    @Singleton
    @Named("finnhubApiKey")
    fun provideFinnhubApiKey(): String = BuildConfig.FINNHUB_API_KEY

    @Provides
    @Singleton
    @Named("alphaVantageApiKey")
    fun provideAlphaVantageApiKey(): String = BuildConfig.ALPHAVANTAGE_API_KEY

    @Provides
    @Singleton
    @Named("openAiApiKey")
    fun provideOpenAiApiKey(): String = BuildConfig.OPENAI_API_KEY

    @Provides
    @Singleton
    @Named("anthropicApiKey")
    fun provideAnthropicApiKey(): String = BuildConfig.ANTHROPIC_API_KEY

    @Provides
    @Singleton
    @Named("nse")
    fun provideNseRetrofit(gson: Gson): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Referer", "https://www.nseindia.com/")
                        .build()
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://www.nseindia.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideNseApiService(@Named("nse") retrofit: Retrofit): NseApiService =
        retrofit.create(NseApiService::class.java)

    @Provides
    @Singleton
    @Named("newsApiKey")
    fun provideNewsApiKey(): String = BuildConfig.NEWS_API_KEY
}
