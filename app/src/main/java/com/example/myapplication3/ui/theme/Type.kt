package com.example.myapplication3.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Full typography scale — larger sizes for beginner readability
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Bold,
        fontSize      = 28.sp,
        lineHeight    = 36.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Bold,
        fontSize      = 22.sp,
        lineHeight    = 28.sp,
        letterSpacing = (-0.15).sp
    ),
    headlineSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Bold,
        fontSize      = 20.sp,
        lineHeight    = 26.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Bold,
        fontSize      = 18.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 16.sp,
        lineHeight    = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 13.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Normal,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Normal,
        fontSize      = 13.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Normal,
        fontSize      = 12.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 13.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        lineHeight    = 15.sp,
        letterSpacing = 0.4.sp
    )
)
