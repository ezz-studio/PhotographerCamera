// AUTO-GENERATED JNI symbol bridge (tools/gen_jni_bridge.py).
// Forwards Java_com_photographercamera_photon_* lookups to the
// com.photographercamera.core.photon.* implementations compiled in this lib.
#include <jni.h>

extern "C" {

jshortArray Java_com_photographercamera_core_photon_color_LutProcessor_resampleLutNative(JNIEnv *env, jobject thiz, jshortArray src_data, jint size, jint curve_type);
jshortArray Java_com_photographercamera_core_photon_color_LutProcessor_resampleSizeNative(JNIEnv *env, jobject thiz, jshortArray src_data, jint src_size, jint target_size);
jboolean Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_encodeGainmapNative(JNIEnv *env, jobject arg1, jobject bitmap, jstring outputPath, jint quality);
jboolean Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_isJpegRNative(JNIEnv *env, jobject arg1, jstring path);
jboolean Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_packageJpegRNative(JNIEnv *env, jobject arg1, jstring baseJpegPath, jstring gainmapJpegPath, jstring outputPath, jint baseColorGamut, jfloatArray ratioMin, jfloatArray ratioMax, jfloatArray gamma, jfloatArray epsilonSdr, jfloatArray epsilonHdr, jfloat displayRatioSdr, jfloat displayRatioHdr, jboolean useBaseColorSpace);
jboolean Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_writeNative(JNIEnv *env, jobject arg1, jobject bitmap, jstring outputPath, jint quality, jbyteArray iccProfile);
void Java_com_photographercamera_core_photon_ml_DnCNNDenoiseEstimator_postprocessNative(JNIEnv *env, jobject arg1, jobject inBuffer, jobject srcBitmap, jobject dstBitmap, jint patchX, jint patchY, jint srcX, jint srcY, jint dstX, jint dstY, jint w, jint h, jint patchW, jint patchH, jfloat strength, jboolean isRgb, jboolean channelsFirst);
void Java_com_photographercamera_core_photon_ml_DnCNNDenoiseEstimator_preprocessNative(JNIEnv *env, jobject arg1, jobject bitmap, jint x, jint y, jint w, jint h, jobject outBuffer, jboolean isRgb, jboolean channelsFirst);
jstring Java_com_photographercamera_core_photon_raw_DcpNativeBridge_parseDcpToJson(JNIEnv *env, jobject arg1, jstring filePath);
jboolean Java_com_photographercamera_core_photon_raw_DngHdrNetProfileGainTableNative_nativeEvaluateDisplayLinearLumaGrid(JNIEnv* env, jobject arg1, jfloatArray coefficients_array, jint source_grid_width, jint source_grid_height, jint source_grid_depth, jint coefficient_count, jfloatArray model_input_array, jint input_width, jint input_height, jint input_channels, jint output_grid_width, jint output_grid_height, jfloat hdr_ratio, jfloat render_min_gain, jfloat render_max_gain, jfloat render_max_gain_blend_threshold, jboolean dehaze_enabled, jfloat dehaze_strength, jfloat dynamic_highlight_strength, jint output_rotation, jfloatArray guide_shifts_array, jfloatArray guide_slopes_array, jfloatArray output_lumas_array, jfloatArray output_dehaze_curve_array, jfloatArray output_metrics_array);
jboolean Java_com_photographercamera_core_photon_raw_DngHdrNetProfileGainTableNative_nativeGenerateGains(JNIEnv* env, jobject arg1, jfloatArray coefficients_array, jfloatArray model_input_array, jint input_width, jint input_height, jint input_channels, jint source_grid_width, jint source_grid_height, jint source_grid_depth, jint coefficient_count, jint output_grid_width, jint output_grid_height, jint point_count, jfloat hdr_ratio, jfloat source_to_short_gain, jfloat renderer_baseline_gain, jfloat render_min_gain, jfloat render_max_gain, jfloat render_max_gain_blend_threshold, jfloat min_table_gain, jfloat max_table_gain, jfloatArray guide_shifts_array, jfloatArray guide_slopes_array, jfloatArray acr_curve_array, jfloatArray dehaze_curve_array, jfloat post_exposure_gain, jfloatArray output_gains_array);
jboolean Java_com_photographercamera_core_photon_raw_DngProfileGainTableValidation_validateGainsNative(JNIEnv* env, jobject arg1, jfloatArray gains, jfloat minimum, jfloat maximum);
void Java_com_photographercamera_core_photon_raw_DngRawData_freeNativeBuffer(JNIEnv *env, jobject arg1, jobject rawDataBuffer);
jint Java_com_photographercamera_core_photon_raw_MgcFullResolutionDenoise_nativeDenoiseRgba16f(JNIEnv* env, jobject arg1, jobject rgba_buffer, jint width, jint height, jint global_origin_x, jint global_origin_y, jint full_width, jint full_height, jboolean input_is_bayer, jint cfa_pattern, jboolean measure_moire_enabled, jboolean apply_lens_shading_in_bayer_aot, jfloatArray lens_shading, jint lens_width, jint lens_height, jfloatArray normalized_rgb_shot_array, jfloatArray normalized_rgb_read_array, jfloatArray normalized_rgb_white_balance_array, jfloatArray prepared_normalized_yuv_read_array, jfloat prepared_normalized_luma_shot, jfloat prepared_normalized_luma_quadratic, jfloat prepared_normalized_chroma_shot, jfloat prepared_normalized_chroma_quadratic, jfloatArray luma_correlation_array, jfloatArray chroma_correlation_array, jfloat denoise_response_offset, jfloat denoise_response_cosine_offset, jshortArray spatial_strength_q8_array, jint spatial_strength_width, jint spatial_strength_height, jboolean luma_enabled, jboolean chroma_enabled, jfloatArray luma_strength_array, jfloatArray luma_outlier_array, jfloatArray luma_revert_array, jfloatArray chroma_strength_array, jfloatArray chroma_outlier_array, jboolean diagnostics_enabled);
jfloat Java_com_photographercamera_core_photon_raw_RawDemosaicProcessor_estimateMgcReferenceSignalNative(JNIEnv* env, jobject arg1, jobject raw_buffer, jint buffer_offset, jint buffer_limit, jint width, jint height, jint row_stride, jint samples_per_pixel, jint first_row_green_phase, jfloat black_level, jint white_level, jfloat normalization_range);
jobject Java_com_photographercamera_core_photon_raw_RawDemosaicProcessor_processDngNative(JNIEnv *env, jobject arg1, jstring filePath, jfloat xr, jfloat yr, jfloat xg, jfloat yg, jfloat xb, jfloat yb, jfloat xw, jfloat yw, jboolean useRawAutoWhiteBalanceEstimate);
jboolean Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeConfigureExposureBounds(JNIEnv*arg0, jobject arg1, jlong handle, jfloat minimum_ev, jfloat maximum_ev);
jlong Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeCreate(JNIEnv* env, jobject arg1, jintArray reference_pixels, jfloatArray portrait_priority_weights, jint width, jint height);
void Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeDestroy(JNIEnv*arg0, jobject arg1, jlong handle);
jfloat Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeGetResultExposureEv(JNIEnv*arg0, jobject arg1, jlong handle);
jfloat Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeNextExposureEv(JNIEnv*arg0, jobject arg1, jlong handle);
jfloatArray Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSolveSingleGridExposure(JNIEnv* env, jobject arg1, jlong handle, jfloatArray candidate_display_linear_lumas, jint columns, jint rows, jfloat minimum_exposure_ev, jfloat maximum_exposure_ev);
jboolean Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitCandidate(JNIEnv* env, jobject arg1, jlong handle, jfloat exposure_ev, jintArray candidate_pixels, jint width, jint height);
jboolean Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitGridCandidate(JNIEnv* env, jobject arg1, jlong handle, jfloat exposure_ev, jfloatArray candidate_display_linear_lumas, jint columns, jint rows);
jboolean Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_bindHardwareBufferImage(JNIEnv *arg0, jobject arg1, jlong image_handle, jint texture_id);
jlong Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_create(JNIEnv *arg0, jobject arg1, jint width, jint height, jboolean front_facing, jfloat strength, jint lookahead_frames);
jlong Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_createHardwareBufferImage(JNIEnv *env, jobject arg1, jobject hardware_buffer);
void Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_destroyHardwareBufferImage(JNIEnv *arg0, jobject arg1, jlong image_handle);
jlong Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processFrame(JNIEnv *env, jobject arg1, jlong handle, jlong source_timestamp_ns, jlong first_row_center_timestamp_ns, jlong exposure_time_ns, jlong frame_duration_ns, jlong rolling_shutter_skew_ns, jfloat inverse_focal_length, jint active_width, jint active_height, jint crop_width, jint crop_height, jint pre_correction_active_width, jint pre_correction_active_height, jfloatArray nominal_lens_intrinsics, jfloatArray row_homographies, jfloatArray output_state);
jboolean Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processGyro(JNIEnv *arg0, jobject arg1, jlong handle, jfloat x, jfloat y, jfloat z, jlong timestamp_ns);
jboolean Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processLensIntrinsics(JNIEnv *arg0, jobject arg1, jlong handle, jfloat fx, jfloat fy, jfloat cx, jfloat cy, jfloat skew, jlong timestamp_ns, jint camera_type);
jboolean Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processLensOffset(JNIEnv *arg0, jobject arg1, jlong handle, jfloat x_shift_pixels, jfloat y_shift_pixels, jlong timestamp_ns, jint camera_type);
void Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_release(JNIEnv *arg0, jobject arg1, jlong handle);
jobject Java_com_photographercamera_core_photon_stack_DirectBufferAllocator_allocateNative(JNIEnv *env, jobject arg1, jlong capacity);
void Java_com_photographercamera_core_photon_stack_DirectBufferAllocator_freeNative(JNIEnv *env, jobject arg1, jobject buffer);
jboolean Java_com_photographercamera_core_photon_stack_DirectBufferPixelPacker_packRgba16fToRgb16(JNIEnv *env, jobject arg1, jobject sourceBuffer, jint width, jint height, jobject destinationBuffer);
jboolean Java_com_photographercamera_core_photon_stack_DirectBufferPixelPacker_unpackRgba16TileToRgb16(JNIEnv *env, jobject arg1, jobject sourceBuffer, jint sourceWidth, jint sourceHeight, jobject destinationBuffer, jint destinationWidth, jint destinationHeight, jint destinationLeft, jint destinationTop);
jboolean Java_com_photographercamera_core_photon_stack_GlesPixelBufferTransfer_uploadRgba16fPboToTexture(JNIEnv *arg0, jobject arg1, jint pixelBufferObject, jint textureId, jint width, jint height);
jint Java_com_photographercamera_core_photon_stack_MgcSabreResolver_nativeResolve(JNIEnv* env, jobject arg1, jobject accumulated_color_buffer, jobject output_rgb_buffer, jint width, jint height, jint cfa_pattern, jfloatArray final_black_level_array, jfloatArray final_gains_array, jint demosaic_white_level, jfloat output_white_level, jfloat demosaic_blend_scale, jfloat demosaic_blend_bias, jfloat demosaic_sharpness_scale);
jint Java_com_photographercamera_core_photon_stack_MgcSpatialRgbMerger_nativeConvertPlanarF16ToFixed16(JNIEnv* env, jobject arg1, jobject buffer, jint sample_count);
jint Java_com_photographercamera_core_photon_stack_MgcSpatialRgbMerger_nativeMerge(JNIEnv* env, jobject arg1, jobjectArray raw_buffers, jintArray raw_offsets_array, jintArray raw_row_strides_array, jobject alignment_buffer, jint alignment_width, jint alignment_height, jobject rejection_buffer, jint rejection_width, jint rejection_height, jfloatArray frame_weights_array, jfloatArray wb_gains_array, jfloatArray input_black_levels_rgb_array, jfloatArray input_black_levels_rggb_array, jfloatArray input_gains_array, jfloat overall_gain, jfloat merge_sharpness, jfloatArray kernel_sigmas_array, jint raw_width, jint raw_height, jint output_width, jint output_height, jint output_storage_width, jint output_storage_height, jint cfa_pattern, jobject output_buffer);
jint Java_com_photographercamera_core_photon_stack_MgcSpatialStrengthMapGenerator_nativeCompute(JNIEnv* env, jobject arg1, jint layout, jobject fused_fixed16_buffer, jint width, jint height, jint cfa_pattern, jobject alignment_buffer, jint alignment_width, jint alignment_height, jobject rejection_buffer, jint rejection_width, jint rejection_height, jint frame_count, jfloatArray input_read_noise_array, jfloatArray input_shot_noise_array, jfloatArray frame_weights_array, jfloatArray kernel_sigmas_array, jfloat rejected_denoise_multiplier, jshortArray output_strength_array, jfloatArray output_read_noise_array, jfloatArray output_shot_noise_array, jfloatArray output_weights_sum_total_diag_0_array, jfloatArray output_weights_sum_total_diag_1_array);
jboolean Java_com_photographercamera_core_photon_stack_MgcSpatialStrengthMapScaler_nativeScaleBilinearU16(JNIEnv *env, jobject arg1, jshortArray sourceArray, jint sourceWidth, jint sourceHeight, jshortArray destinationArray, jint destinationWidth, jint destinationHeight);
jbyteArray Java_com_photographercamera_core_photon_stack_SuperResolutionDngWriter_encodeLosslessJpegNative(JNIEnv *env, jobject arg1, jobject rawBuffer, jint width, jint height, jint samplesPerPixel, jint bitsPerSample, jint rowStep, jint colStep);
void Java_com_photographercamera_core_photon_stack_YuvProcessor_processToBitmap(JNIEnv *env, jobject arg1, jobject yBuffer, jobject uBuffer, jobject vBuffer, jint width, jint height, jint yRowStride, jint uvRowStride, jint uvPixelStride, jint rotation, jint targetWR, jint targetHR, jint format, jobject outBitmap8);
jboolean Java_com_photographercamera_core_photon_stack_YuvProcessor_processToFile(JNIEnv *env, jobject arg1, jobject yBuffer, jobject uBuffer, jobject vBuffer, jint width, jint height, jint yRowStride, jint uvRowStride, jint uvPixelStride, jint rotation, jint format, jstring outputPath);

jshortArray Java_com_photographercamera_photon_lut_LutProcessor_resampleLutNative(JNIEnv *env, jobject thiz, jshortArray src_data, jint size, jint curve_type) {
  return Java_com_photographercamera_core_photon_color_LutProcessor_resampleLutNative(env, thiz, src_data, size, curve_type);
}

jshortArray Java_com_photographercamera_photon_lut_LutProcessor_resampleSizeNative(JNIEnv *env, jobject thiz, jshortArray src_data, jint src_size, jint target_size) {
  return Java_com_photographercamera_core_photon_color_LutProcessor_resampleSizeNative(env, thiz, src_data, src_size, target_size);
}

jboolean Java_com_photographercamera_photon_gallery_Jpeg444ExportEncoder_encodeGainmapNative(JNIEnv *env, jobject arg1, jobject bitmap, jstring outputPath, jint quality) {
  return Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_encodeGainmapNative(env, arg1, bitmap, outputPath, quality);
}

jboolean Java_com_photographercamera_photon_gallery_Jpeg444ExportEncoder_isJpegRNative(JNIEnv *env, jobject arg1, jstring path) {
  return Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_isJpegRNative(env, arg1, path);
}

jboolean Java_com_photographercamera_photon_gallery_Jpeg444ExportEncoder_packageJpegRNative(JNIEnv *env, jobject arg1, jstring baseJpegPath, jstring gainmapJpegPath, jstring outputPath, jint baseColorGamut, jfloatArray ratioMin, jfloatArray ratioMax, jfloatArray gamma, jfloatArray epsilonSdr, jfloatArray epsilonHdr, jfloat displayRatioSdr, jfloat displayRatioHdr, jboolean useBaseColorSpace) {
  return Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_packageJpegRNative(env, arg1, baseJpegPath, gainmapJpegPath, outputPath, baseColorGamut, ratioMin, ratioMax, gamma, epsilonSdr, epsilonHdr, displayRatioSdr, displayRatioHdr, useBaseColorSpace);
}

jboolean Java_com_photographercamera_photon_gallery_Jpeg444ExportEncoder_writeNative(JNIEnv *env, jobject arg1, jobject bitmap, jstring outputPath, jint quality, jbyteArray iccProfile) {
  return Java_com_photographercamera_core_photon_gallery_Jpeg444ExportEncoder_writeNative(env, arg1, bitmap, outputPath, quality, iccProfile);
}

void Java_com_photographercamera_photon_ml_DnCNNDenoiseEstimator_postprocessNative(JNIEnv *env, jobject arg1, jobject inBuffer, jobject srcBitmap, jobject dstBitmap, jint patchX, jint patchY, jint srcX, jint srcY, jint dstX, jint dstY, jint w, jint h, jint patchW, jint patchH, jfloat strength, jboolean isRgb, jboolean channelsFirst) {
  Java_com_photographercamera_core_photon_ml_DnCNNDenoiseEstimator_postprocessNative(env, arg1, inBuffer, srcBitmap, dstBitmap, patchX, patchY, srcX, srcY, dstX, dstY, w, h, patchW, patchH, strength, isRgb, channelsFirst);
}

void Java_com_photographercamera_photon_ml_DnCNNDenoiseEstimator_preprocessNative(JNIEnv *env, jobject arg1, jobject bitmap, jint x, jint y, jint w, jint h, jobject outBuffer, jboolean isRgb, jboolean channelsFirst) {
  Java_com_photographercamera_core_photon_ml_DnCNNDenoiseEstimator_preprocessNative(env, arg1, bitmap, x, y, w, h, outBuffer, isRgb, channelsFirst);
}

jstring Java_com_photographercamera_photon_raw_DcpNativeBridge_parseDcpToJson(JNIEnv *env, jobject arg1, jstring filePath) {
  return Java_com_photographercamera_core_photon_raw_DcpNativeBridge_parseDcpToJson(env, arg1, filePath);
}

jboolean Java_com_photographercamera_photon_raw_DngHdrNetProfileGainTableNative_nativeEvaluateDisplayLinearLumaGrid(JNIEnv* env, jobject arg1, jfloatArray coefficients_array, jint source_grid_width, jint source_grid_height, jint source_grid_depth, jint coefficient_count, jfloatArray model_input_array, jint input_width, jint input_height, jint input_channels, jint output_grid_width, jint output_grid_height, jfloat hdr_ratio, jfloat render_min_gain, jfloat render_max_gain, jfloat render_max_gain_blend_threshold, jboolean dehaze_enabled, jfloat dehaze_strength, jfloat dynamic_highlight_strength, jint output_rotation, jfloatArray guide_shifts_array, jfloatArray guide_slopes_array, jfloatArray output_lumas_array, jfloatArray output_dehaze_curve_array, jfloatArray output_metrics_array) {
  return Java_com_photographercamera_core_photon_raw_DngHdrNetProfileGainTableNative_nativeEvaluateDisplayLinearLumaGrid(env, arg1, coefficients_array, source_grid_width, source_grid_height, source_grid_depth, coefficient_count, model_input_array, input_width, input_height, input_channels, output_grid_width, output_grid_height, hdr_ratio, render_min_gain, render_max_gain, render_max_gain_blend_threshold, dehaze_enabled, dehaze_strength, dynamic_highlight_strength, output_rotation, guide_shifts_array, guide_slopes_array, output_lumas_array, output_dehaze_curve_array, output_metrics_array);
}

jboolean Java_com_photographercamera_photon_raw_DngHdrNetProfileGainTableNative_nativeGenerateGains(JNIEnv* env, jobject arg1, jfloatArray coefficients_array, jfloatArray model_input_array, jint input_width, jint input_height, jint input_channels, jint source_grid_width, jint source_grid_height, jint source_grid_depth, jint coefficient_count, jint output_grid_width, jint output_grid_height, jint point_count, jfloat hdr_ratio, jfloat source_to_short_gain, jfloat renderer_baseline_gain, jfloat render_min_gain, jfloat render_max_gain, jfloat render_max_gain_blend_threshold, jfloat min_table_gain, jfloat max_table_gain, jfloatArray guide_shifts_array, jfloatArray guide_slopes_array, jfloatArray acr_curve_array, jfloatArray dehaze_curve_array, jfloat post_exposure_gain, jfloatArray output_gains_array) {
  return Java_com_photographercamera_core_photon_raw_DngHdrNetProfileGainTableNative_nativeGenerateGains(env, arg1, coefficients_array, model_input_array, input_width, input_height, input_channels, source_grid_width, source_grid_height, source_grid_depth, coefficient_count, output_grid_width, output_grid_height, point_count, hdr_ratio, source_to_short_gain, renderer_baseline_gain, render_min_gain, render_max_gain, render_max_gain_blend_threshold, min_table_gain, max_table_gain, guide_shifts_array, guide_slopes_array, acr_curve_array, dehaze_curve_array, post_exposure_gain, output_gains_array);
}

jboolean Java_com_photographercamera_photon_raw_DngProfileGainTableValidation_validateGainsNative(JNIEnv* env, jobject arg1, jfloatArray gains, jfloat minimum, jfloat maximum) {
  return Java_com_photographercamera_core_photon_raw_DngProfileGainTableValidation_validateGainsNative(env, arg1, gains, minimum, maximum);
}

void Java_com_photographercamera_photon_raw_DngRawData_freeNativeBuffer(JNIEnv *env, jobject arg1, jobject rawDataBuffer) {
  Java_com_photographercamera_core_photon_raw_DngRawData_freeNativeBuffer(env, arg1, rawDataBuffer);
}

jint Java_com_photographercamera_photon_raw_MgcFullResolutionDenoise_nativeDenoiseRgba16f(JNIEnv* env, jobject arg1, jobject rgba_buffer, jint width, jint height, jint global_origin_x, jint global_origin_y, jint full_width, jint full_height, jboolean input_is_bayer, jint cfa_pattern, jboolean measure_moire_enabled, jboolean apply_lens_shading_in_bayer_aot, jfloatArray lens_shading, jint lens_width, jint lens_height, jfloatArray normalized_rgb_shot_array, jfloatArray normalized_rgb_read_array, jfloatArray normalized_rgb_white_balance_array, jfloatArray prepared_normalized_yuv_read_array, jfloat prepared_normalized_luma_shot, jfloat prepared_normalized_luma_quadratic, jfloat prepared_normalized_chroma_shot, jfloat prepared_normalized_chroma_quadratic, jfloatArray luma_correlation_array, jfloatArray chroma_correlation_array, jfloat denoise_response_offset, jfloat denoise_response_cosine_offset, jshortArray spatial_strength_q8_array, jint spatial_strength_width, jint spatial_strength_height, jboolean luma_enabled, jboolean chroma_enabled, jfloatArray luma_strength_array, jfloatArray luma_outlier_array, jfloatArray luma_revert_array, jfloatArray chroma_strength_array, jfloatArray chroma_outlier_array, jboolean diagnostics_enabled) {
  return Java_com_photographercamera_core_photon_raw_MgcFullResolutionDenoise_nativeDenoiseRgba16f(env, arg1, rgba_buffer, width, height, global_origin_x, global_origin_y, full_width, full_height, input_is_bayer, cfa_pattern, measure_moire_enabled, apply_lens_shading_in_bayer_aot, lens_shading, lens_width, lens_height, normalized_rgb_shot_array, normalized_rgb_read_array, normalized_rgb_white_balance_array, prepared_normalized_yuv_read_array, prepared_normalized_luma_shot, prepared_normalized_luma_quadratic, prepared_normalized_chroma_shot, prepared_normalized_chroma_quadratic, luma_correlation_array, chroma_correlation_array, denoise_response_offset, denoise_response_cosine_offset, spatial_strength_q8_array, spatial_strength_width, spatial_strength_height, luma_enabled, chroma_enabled, luma_strength_array, luma_outlier_array, luma_revert_array, chroma_strength_array, chroma_outlier_array, diagnostics_enabled);
}

jfloat Java_com_photographercamera_photon_raw_RawDemosaicProcessor_estimateMgcReferenceSignalNative(JNIEnv* env, jobject arg1, jobject raw_buffer, jint buffer_offset, jint buffer_limit, jint width, jint height, jint row_stride, jint samples_per_pixel, jint first_row_green_phase, jfloat black_level, jint white_level, jfloat normalization_range) {
  return Java_com_photographercamera_core_photon_raw_RawDemosaicProcessor_estimateMgcReferenceSignalNative(env, arg1, raw_buffer, buffer_offset, buffer_limit, width, height, row_stride, samples_per_pixel, first_row_green_phase, black_level, white_level, normalization_range);
}

jobject Java_com_photographercamera_photon_raw_RawDemosaicProcessor_processDngNative(JNIEnv *env, jobject arg1, jstring filePath, jfloat xr, jfloat yr, jfloat xg, jfloat yg, jfloat xb, jfloat yb, jfloat xw, jfloat yw, jboolean useRawAutoWhiteBalanceEstimate) {
  return Java_com_photographercamera_core_photon_raw_RawDemosaicProcessor_processDngNative(env, arg1, filePath, xr, yr, xg, yg, xb, yb, xw, yw, useRawAutoWhiteBalanceEstimate);
}

jboolean Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeConfigureExposureBounds(JNIEnv*arg0, jobject arg1, jlong handle, jfloat minimum_ev, jfloat maximum_ev) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeConfigureExposureBounds(arg0, arg1, handle, minimum_ev, maximum_ev);
}

jlong Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeCreate(JNIEnv* env, jobject arg1, jintArray reference_pixels, jfloatArray portrait_priority_weights, jint width, jint height) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeCreate(env, arg1, reference_pixels, portrait_priority_weights, width, height);
}

void Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeDestroy(JNIEnv*arg0, jobject arg1, jlong handle) {
  Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeDestroy(arg0, arg1, handle);
}

jfloat Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeGetResultExposureEv(JNIEnv*arg0, jobject arg1, jlong handle) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeGetResultExposureEv(arg0, arg1, handle);
}

jfloat Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeNextExposureEv(JNIEnv*arg0, jobject arg1, jlong handle) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeNextExposureEv(arg0, arg1, handle);
}

jfloatArray Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSolveSingleGridExposure(JNIEnv* env, jobject arg1, jlong handle, jfloatArray candidate_display_linear_lumas, jint columns, jint rows, jfloat minimum_exposure_ev, jfloat maximum_exposure_ev) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSolveSingleGridExposure(env, arg1, handle, candidate_display_linear_lumas, columns, rows, minimum_exposure_ev, maximum_exposure_ev);
}

jboolean Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitCandidate(JNIEnv* env, jobject arg1, jlong handle, jfloat exposure_ev, jintArray candidate_pixels, jint width, jint height) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitCandidate(env, arg1, handle, exposure_ev, candidate_pixels, width, height);
}

jboolean Java_com_photographercamera_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitGridCandidate(JNIEnv* env, jobject arg1, jlong handle, jfloat exposure_ev, jfloatArray candidate_display_linear_lumas, jint columns, jint rows) {
  return Java_com_photographercamera_core_photon_raw_RawLegacyAutoExposureNativeBridge_nativeSubmitGridCandidate(env, arg1, handle, exposure_ev, candidate_display_linear_lumas, columns, rows);
}

jboolean Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_bindHardwareBufferImage(JNIEnv *arg0, jobject arg1, jlong image_handle, jint texture_id) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_bindHardwareBufferImage(arg0, arg1, image_handle, texture_id);
}

jlong Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_create(JNIEnv *arg0, jobject arg1, jint width, jint height, jboolean front_facing, jfloat strength, jint lookahead_frames) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_create(arg0, arg1, width, height, front_facing, strength, lookahead_frames);
}

jlong Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_createHardwareBufferImage(JNIEnv *env, jobject arg1, jobject hardware_buffer) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_createHardwareBufferImage(env, arg1, hardware_buffer);
}

void Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_destroyHardwareBufferImage(JNIEnv *arg0, jobject arg1, jlong image_handle) {
  Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_destroyHardwareBufferImage(arg0, arg1, image_handle);
}

jlong Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_processFrame(JNIEnv *env, jobject arg1, jlong handle, jlong source_timestamp_ns, jlong first_row_center_timestamp_ns, jlong exposure_time_ns, jlong frame_duration_ns, jlong rolling_shutter_skew_ns, jfloat inverse_focal_length, jint active_width, jint active_height, jint crop_width, jint crop_height, jint pre_correction_active_width, jint pre_correction_active_height, jfloatArray nominal_lens_intrinsics, jfloatArray row_homographies, jfloatArray output_state) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processFrame(env, arg1, handle, source_timestamp_ns, first_row_center_timestamp_ns, exposure_time_ns, frame_duration_ns, rolling_shutter_skew_ns, inverse_focal_length, active_width, active_height, crop_width, crop_height, pre_correction_active_width, pre_correction_active_height, nominal_lens_intrinsics, row_homographies, output_state);
}

jboolean Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_processGyro(JNIEnv *arg0, jobject arg1, jlong handle, jfloat x, jfloat y, jfloat z, jlong timestamp_ns) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processGyro(arg0, arg1, handle, x, y, z, timestamp_ns);
}

jboolean Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_processLensIntrinsics(JNIEnv *arg0, jobject arg1, jlong handle, jfloat fx, jfloat fy, jfloat cx, jfloat cy, jfloat skew, jlong timestamp_ns, jint camera_type) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processLensIntrinsics(arg0, arg1, handle, fx, fy, cx, cy, skew, timestamp_ns, camera_type);
}

jboolean Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_processLensOffset(JNIEnv *arg0, jobject arg1, jlong handle, jfloat x_shift_pixels, jfloat y_shift_pixels, jlong timestamp_ns, jint camera_type) {
  return Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_processLensOffset(arg0, arg1, handle, x_shift_pixels, y_shift_pixels, timestamp_ns, camera_type);
}

void Java_com_photographercamera_photon_stabilization_MgcEisNativeBridge_release(JNIEnv *arg0, jobject arg1, jlong handle) {
  Java_com_photographercamera_core_photon_stabilization_MgcEisNativeBridge_release(arg0, arg1, handle);
}

jobject Java_com_photographercamera_photon_utils_DirectBufferAllocator_allocateNative(JNIEnv *env, jobject arg1, jlong capacity) {
  return Java_com_photographercamera_core_photon_stack_DirectBufferAllocator_allocateNative(env, arg1, capacity);
}

void Java_com_photographercamera_photon_utils_DirectBufferAllocator_freeNative(JNIEnv *env, jobject arg1, jobject buffer) {
  Java_com_photographercamera_core_photon_stack_DirectBufferAllocator_freeNative(env, arg1, buffer);
}

jboolean Java_com_photographercamera_photon_utils_DirectBufferPixelPacker_packRgba16fToRgb16(JNIEnv *env, jobject arg1, jobject sourceBuffer, jint width, jint height, jobject destinationBuffer) {
  return Java_com_photographercamera_core_photon_stack_DirectBufferPixelPacker_packRgba16fToRgb16(env, arg1, sourceBuffer, width, height, destinationBuffer);
}

jboolean Java_com_photographercamera_photon_utils_DirectBufferPixelPacker_unpackRgba16TileToRgb16(JNIEnv *env, jobject arg1, jobject sourceBuffer, jint sourceWidth, jint sourceHeight, jobject destinationBuffer, jint destinationWidth, jint destinationHeight, jint destinationLeft, jint destinationTop) {
  return Java_com_photographercamera_core_photon_stack_DirectBufferPixelPacker_unpackRgba16TileToRgb16(env, arg1, sourceBuffer, sourceWidth, sourceHeight, destinationBuffer, destinationWidth, destinationHeight, destinationLeft, destinationTop);
}

jboolean Java_com_photographercamera_photon_processor_GlesPixelBufferTransfer_uploadRgba16fPboToTexture(JNIEnv *arg0, jobject arg1, jint pixelBufferObject, jint textureId, jint width, jint height) {
  return Java_com_photographercamera_core_photon_stack_GlesPixelBufferTransfer_uploadRgba16fPboToTexture(arg0, arg1, pixelBufferObject, textureId, width, height);
}

jint Java_com_photographercamera_photon_processor_MgcSabreResolver_nativeResolve(JNIEnv* env, jobject arg1, jobject accumulated_color_buffer, jobject output_rgb_buffer, jint width, jint height, jint cfa_pattern, jfloatArray final_black_level_array, jfloatArray final_gains_array, jint demosaic_white_level, jfloat output_white_level, jfloat demosaic_blend_scale, jfloat demosaic_blend_bias, jfloat demosaic_sharpness_scale) {
  return Java_com_photographercamera_core_photon_stack_MgcSabreResolver_nativeResolve(env, arg1, accumulated_color_buffer, output_rgb_buffer, width, height, cfa_pattern, final_black_level_array, final_gains_array, demosaic_white_level, output_white_level, demosaic_blend_scale, demosaic_blend_bias, demosaic_sharpness_scale);
}

jint Java_com_photographercamera_photon_processor_MgcSpatialRgbMerger_nativeConvertPlanarF16ToFixed16(JNIEnv* env, jobject arg1, jobject buffer, jint sample_count) {
  return Java_com_photographercamera_core_photon_stack_MgcSpatialRgbMerger_nativeConvertPlanarF16ToFixed16(env, arg1, buffer, sample_count);
}

jint Java_com_photographercamera_photon_processor_MgcSpatialRgbMerger_nativeMerge(JNIEnv* env, jobject arg1, jobjectArray raw_buffers, jintArray raw_offsets_array, jintArray raw_row_strides_array, jobject alignment_buffer, jint alignment_width, jint alignment_height, jobject rejection_buffer, jint rejection_width, jint rejection_height, jfloatArray frame_weights_array, jfloatArray wb_gains_array, jfloatArray input_black_levels_rgb_array, jfloatArray input_black_levels_rggb_array, jfloatArray input_gains_array, jfloat overall_gain, jfloat merge_sharpness, jfloatArray kernel_sigmas_array, jint raw_width, jint raw_height, jint output_width, jint output_height, jint output_storage_width, jint output_storage_height, jint cfa_pattern, jobject output_buffer) {
  return Java_com_photographercamera_core_photon_stack_MgcSpatialRgbMerger_nativeMerge(env, arg1, raw_buffers, raw_offsets_array, raw_row_strides_array, alignment_buffer, alignment_width, alignment_height, rejection_buffer, rejection_width, rejection_height, frame_weights_array, wb_gains_array, input_black_levels_rgb_array, input_black_levels_rggb_array, input_gains_array, overall_gain, merge_sharpness, kernel_sigmas_array, raw_width, raw_height, output_width, output_height, output_storage_width, output_storage_height, cfa_pattern, output_buffer);
}

jint Java_com_photographercamera_photon_processor_MgcSpatialStrengthMapGenerator_nativeCompute(JNIEnv* env, jobject arg1, jint layout, jobject fused_fixed16_buffer, jint width, jint height, jint cfa_pattern, jobject alignment_buffer, jint alignment_width, jint alignment_height, jobject rejection_buffer, jint rejection_width, jint rejection_height, jint frame_count, jfloatArray input_read_noise_array, jfloatArray input_shot_noise_array, jfloatArray frame_weights_array, jfloatArray kernel_sigmas_array, jfloat rejected_denoise_multiplier, jshortArray output_strength_array, jfloatArray output_read_noise_array, jfloatArray output_shot_noise_array, jfloatArray output_weights_sum_total_diag_0_array, jfloatArray output_weights_sum_total_diag_1_array) {
  return Java_com_photographercamera_core_photon_stack_MgcSpatialStrengthMapGenerator_nativeCompute(env, arg1, layout, fused_fixed16_buffer, width, height, cfa_pattern, alignment_buffer, alignment_width, alignment_height, rejection_buffer, rejection_width, rejection_height, frame_count, input_read_noise_array, input_shot_noise_array, frame_weights_array, kernel_sigmas_array, rejected_denoise_multiplier, output_strength_array, output_read_noise_array, output_shot_noise_array, output_weights_sum_total_diag_0_array, output_weights_sum_total_diag_1_array);
}

jboolean Java_com_photographercamera_photon_processor_MgcSpatialStrengthMapScaler_nativeScaleBilinearU16(JNIEnv *env, jobject arg1, jshortArray sourceArray, jint sourceWidth, jint sourceHeight, jshortArray destinationArray, jint destinationWidth, jint destinationHeight) {
  return Java_com_photographercamera_core_photon_stack_MgcSpatialStrengthMapScaler_nativeScaleBilinearU16(env, arg1, sourceArray, sourceWidth, sourceHeight, destinationArray, destinationWidth, destinationHeight);
}

jbyteArray Java_com_photographercamera_photon_utils_SuperResolutionDngWriter_encodeLosslessJpegNative(JNIEnv *env, jobject arg1, jobject rawBuffer, jint width, jint height, jint samplesPerPixel, jint bitsPerSample, jint rowStep, jint colStep) {
  return Java_com_photographercamera_core_photon_stack_SuperResolutionDngWriter_encodeLosslessJpegNative(env, arg1, rawBuffer, width, height, samplesPerPixel, bitsPerSample, rowStep, colStep);
}

void Java_com_photographercamera_photon_utils_YuvProcessor_processToBitmap(JNIEnv *env, jobject arg1, jobject yBuffer, jobject uBuffer, jobject vBuffer, jint width, jint height, jint yRowStride, jint uvRowStride, jint uvPixelStride, jint rotation, jint targetWR, jint targetHR, jint format, jobject outBitmap8) {
  Java_com_photographercamera_core_photon_stack_YuvProcessor_processToBitmap(env, arg1, yBuffer, uBuffer, vBuffer, width, height, yRowStride, uvRowStride, uvPixelStride, rotation, targetWR, targetHR, format, outBitmap8);
}

jboolean Java_com_photographercamera_photon_utils_YuvProcessor_processToFile(JNIEnv *env, jobject arg1, jobject yBuffer, jobject uBuffer, jobject vBuffer, jint width, jint height, jint yRowStride, jint uvRowStride, jint uvPixelStride, jint rotation, jint format, jstring outputPath) {
  return Java_com_photographercamera_core_photon_stack_YuvProcessor_processToFile(env, arg1, yBuffer, uBuffer, vBuffer, width, height, yRowStride, uvRowStride, uvPixelStride, rotation, format, outputPath);
}

} // extern "C"
