package com.example.ink2text.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class RecognizeResponse(val text: String)

interface ApiService {
    @Multipart
    @POST("api/recognize")
    suspend fun recognizeText(@Part image: MultipartBody.Part): Response<RecognizeResponse>
}
