package com.d3vildog.sportspredictor

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

fun JSONObject.toDoubleMap(): Map<String, Double> =
    keys().asSequence().associateWith { getDouble(it) }

fun JSONObject.toDoubleListMap(): Map<String, List<Double>> =
    keys().asSequence().associateWith { key ->
        val arr = getJSONArray(key)
        (0 until arr.length()).map { arr.getDouble(it) }
    }
