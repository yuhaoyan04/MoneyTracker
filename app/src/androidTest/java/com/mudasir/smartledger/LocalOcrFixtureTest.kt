package com.mudasir.smartledger

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mudasir.smartledger.capture.VisualCheckoutParser
import com.mudasir.smartledger.capture.VisualText
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Optional private fixture is injected by adb for a user-authorized, local-only check. */
@RunWith(AndroidJUnit4::class)
class LocalOcrFixtureTest {
    @Test fun probeAlipayAmountCrop() {
        val args = InstrumentationRegistry.getArguments()
        val target = args.getString("fixture_expected")
        val file = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("private_ocr_fixture.png")
        assumeTrue(args.getString("fixture_mode") == "alipay_crop" && target != null && file.exists())
        val bytes = android.util.Base64.decode(file.readBytes(), android.util.Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode local fixture")
        val crop = android.graphics.Bitmap.createBitmap(bitmap, bitmap.width / 5, bitmap.height * 27 / 100,
            bitmap.width * 3 / 5, bitmap.height * 10 / 100)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 30, TimeUnit.SECONDS)
            val full = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            val lines = full.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                VisualText(line.text, b.left, b.top, b.right, b.bottom)
            }
            val clean = result.text.replace(Regex("\\s+"), "")
            val matched = target != null && target in clean
            val pending = com.mudasir.smartledger.capture.AlipayVisualPolicy.isPendingCashier(lines, bitmap.height)
            val parsed = com.mudasir.smartledger.capture.AlipayVisualPolicy.cropAmount(result.text)
            val parsedMatches = target != null && parsed?.compareTo(BigDecimal(target)) == 0
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("crop_exact_match", matched.toString())
                putString("pending_page_matched", pending.toString())
                putString("parsed_amount_matched", parsedMatches.toString())
                putInt("recognized_line_count", result.textBlocks.sumOf { it.lines.size })
            })
            assertTrue("Amount crop or pending page did not match", matched && pending && parsedMatches)
        } finally {
            recognizer.close(); crop.recycle(); bitmap.recycle(); file.delete()
        }
    }

    @Test fun inspectCashierStructure() {
        val args = InstrumentationRegistry.getArguments()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = instrumentation.targetContext.filesDir.resolve("private_ocr_fixture.png")
        assumeTrue(args.getString("fixture_mode") == "cashier" && file.exists())
        val bytes = android.util.Base64.decode(file.readBytes(), android.util.Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode local fixture")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            val evidence = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                if (!(line.text.contains('¥') || line.text.contains('￥') || line.text.contains("支付") || line.text.contains("付款"))) return@mapNotNull null
                val masked = line.text.map { ch -> when {
                    ch.isDigit() -> '#'
                    ch in "确认付款支付宝微信银行卡¥￥.," -> ch
                    else -> '*'
                } }.joinToString("").take(80)
                "$masked@${b.left * 100 / bitmap.width},${b.top * 100 / bitmap.height},${b.width() * 100 / bitmap.width},${b.height() * 100 / bitmap.height}"
            }.joinToString(";")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("masked_structure", evidence) })
        } finally {
            recognizer.close(); bitmap.recycle()
            if (args.getString("fixture_keep") != "true") file.delete()
        }
    }

    @Test fun paymentButtonAmountMatchesExpected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = InstrumentationRegistry.getArguments().getString("fixture_expected")
        val file = instrumentation.targetContext.filesDir.resolve("private_ocr_fixture.png")
        assumeTrue(expected != null && file.exists())
        val target = expected ?: return
        val bytes = android.util.Base64.decode(file.readBytes(), android.util.Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode local fixture")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                VisualText(line.text, b.left, b.top, b.right, b.bottom)
            }
            val value = VisualCheckoutParser.amount(lines, bitmap.width, bitmap.height)
            if (value == null || value.compareTo(BigDecimal(target)) != 0) {
                val evidence = lines.filter { it.top > bitmap.height * 2 / 3 && ("支付" in it.text || "付款" in it.text || target in it.text) }
                    .joinToString(";") { line ->
                        val masked = line.text.map { ch -> when {
                            ch.isDigit() -> '#'
                            ch in "立即确认支付付款¥￥.," -> ch
                            else -> '*'
                        } }.joinToString("").take(80)
                        "$masked@${line.left * 10 / bitmap.width},${line.top * 10 / bitmap.height}:expected=${target in line.text}"
                    }
                val flags = listOf("支付成功", "付款成功", "支付失败", "付款失败", "订单已取消", "全部订单", "我的订单", "订单列表", "历史订单", "订单记录", "待付款", "待发货", "待收货", "待评价", "已完成")
                    .filter { flag -> lines.any { flag in it.text } }.joinToString(",")
                throw AssertionError("OCR did not match; extracted=${value != null}; page flags=$flags; bottom-area masked evidence: $evidence")
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
            if (InstrumentationRegistry.getArguments().getString("fixture_keep") != "true") file.delete()
        }
    }
}
