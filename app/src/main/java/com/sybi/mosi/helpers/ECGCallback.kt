package com.sybi.mosi.helpers

import android.graphics.Bitmap

interface ECGCallback {
    fun onMeasureResult(
        heartRate: Int,
        pAxis: String,
        qrsAxis: String,
        tAxis: String,
        prInterval: String,
        qrsDuration: String,
        qtd: String,
        qtc: String,
        rv5: String,
        sv1: String,
        resCode: String,
        ecgImage: Bitmap
    )

    fun onMeasureError()
}