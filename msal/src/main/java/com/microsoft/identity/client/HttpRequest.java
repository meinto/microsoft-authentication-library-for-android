// Copyright (c) Microsoft Corporation.
// All rights reserved.
//
// This code is licensed under the MIT License.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package com.microsoft.identity.client;

import android.os.Build;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Internal class for handling http request.
 */
final class HttpRequest {
    // static constant variables
    private static final String TAG = HttpRequest.class.getSimpleName();

    private static final String REQUEST_METHOD_GET = "GET";
    private static final String REQUEST_METHOD_POST = "POST";
    private static final String HOST = "Host";
    private static final String HTTP_PROTOCOL = "http";
    private static final String HTTPS_PROTOCOL = "https";
    private static final String HTTP_PORT_NUMBER = ":80";
    private static final String HTTPS_PORT_NUMBER = ":443";
    // TODO: should we make the timeout configuarable at client side.
    private static final int CONNECT_TIME_OUT = 3000;
    private static final int READ_TIME_OUT = 3000;
    /** The waiting time before doing retry to prevent hitting the server immediately failure. */
    private static final int RETRY_TIME_WAITING_PERIOD = 1000;

    // class variables
    private final URL mRequestUrl;
    private final byte[] mRequestContent;
    private final String mRequestContentType;
    private final Map<String, String> mRequestHeaders = new HashMap<>();

    /**
     * Constructor for {@link HttpRequest} with request {@link URL} and request headers.
     * @param requestUrl The {@link URL} to make the http request.
     * @param requestHeaders Headers used to send the http request.
     */
    private HttpRequest(final URL requestUrl, final Map<String, String> requestHeaders) {
        this(requestUrl, requestHeaders, null, null);
    }

    /**
     * Constructor for {@link HttpRequest} with request {@link URL}, headers, post message and the request content
     * type.
     * @param requestUrl The {@link URL} to make the http request.
     * @param requestHeaders Headers used to send the http request.
     * @param requestContent Post message sent in the post request.
     * @param requestContentType Request content type.
     */
    private HttpRequest(final URL requestUrl, final Map<String, String> requestHeaders, final byte[] requestContent,
                       final String requestContentType) {
        // verify the request url first.
        if (requestUrl == null) {
            throw new IllegalArgumentException("null requestUrl");
        }

        if (!HTTP_PROTOCOL.equalsIgnoreCase(requestUrl.getProtocol())
                && !HTTPS_PROTOCOL.equalsIgnoreCase(requestUrl.getProtocol())) {
            throw new IllegalArgumentException("invalid requestUrl");
        }

        mRequestUrl = requestUrl;

        mRequestHeaders.put(HOST, getUrlAuthority(requestUrl));
        mRequestHeaders.putAll(requestHeaders);

        mRequestContent = requestContent;
        mRequestContentType = requestContentType;
    }

    /**
     * Send post request {@link URL}, headers, post message and the request content type.
     * @param requestUrl The {@link URL} to make the http request.
     * @param requestHeaders Headers used to send the http request.
     * @param requestContent Post message sent in the post request.
     * @param requestContentType Request content type.
     */
    public static HttpResponse sendPost(final URL requestUrl, final Map<String, String> requestHeaders,
                                        final byte[] requestContent, final String requestContentType)
            throws IOException, MSALAuthenticationException {
        final HttpRequest httpRequest = new HttpRequest(requestUrl, requestHeaders, requestContent, requestContentType);
        return httpRequest.send(REQUEST_METHOD_POST);
    }

    /**
     * Send Get request {@link URL} and request headers.
     * @param requestUrl The {@link URL} to make the http request.
     * @param requestHeaders Headers used to send the http request.
     */
    public static HttpResponse sendGet(final URL requestUrl, final Map<String, String> requestHeaders)
            throws IOException, MSALAuthenticationException {
        final HttpRequest httpRequest = new HttpRequest(requestUrl, requestHeaders);
        return httpRequest.send(REQUEST_METHOD_GET);
    }

    /**
     * Get the authority from given request URL.
     */
    private String getUrlAuthority(final URL requestUrl) {
        String authority = requestUrl.getAuthority();

        if (requestUrl.getPort() == -1) {
            if (HTTP_PROTOCOL.equalsIgnoreCase(requestUrl.getProtocol())) {
                authority += HTTP_PORT_NUMBER;
            } else if (HTTPS_PROTOCOL.equalsIgnoreCase(requestUrl.getProtocol())) {
                authority += HTTPS_PORT_NUMBER;
            }
        }

        return authority;
    }

    /**
     * Send http request.
     */
    private HttpResponse send(final String requestMethod) throws IOException, MSALAuthenticationException {
        final HttpResponse response;
        try {
            response = sendWithRetry(requestMethod);
        } catch (final SocketTimeoutException socketTimeoutException) {
            throw new MSALAuthenticationException(MSALError.RETRY_FAILED_WITH_NETWORK_TIME_OUT,
                    socketTimeoutException.getMessage(), socketTimeoutException);
        }

        if (response != null && isRetryableError(response.getStatusCode())) {
            throw new MSALAuthenticationException(MSALError.RETRY_FAILED_WITH_SERVER_ERROR,
                    "StatusCode: " + String.valueOf(response.getStatusCode()) + ";ResponseBody: "
                            + response.getResponseBody());
        }

        return response;
    }

    /**
     * Execute the send request, and retry if needed. Retry happens on all the endpoint when receiving
     * {@link SocketTimeoutException} or retryable error 500/503/504.
     */
    private HttpResponse sendWithRetry(final String requestMethod) throws IOException {
        HttpResponse httpResponse;
        try {
            httpResponse = executeHttpSend(requestMethod);
        } catch (final SocketTimeoutException socketTimeoutException) {
            // In android, network timeout is thrown as the SocketTimeOutException, we need to catch this and perform
            // retry. If retry also fails with timeout, the socketTimeoutException will be bubbled up
            waitingBeforeRetry();
            return executeHttpSend(requestMethod);
        }

        if (isRetryableError(httpResponse.getStatusCode())) {
            // retry if we get 500/503/504
            waitingBeforeRetry();
            return executeHttpSend(requestMethod);
        }

        return httpResponse;
    }

    private HttpResponse executeHttpSend(final String requestMethod) throws IOException {
        final HttpURLConnection urlConnection = setupConnection();
        urlConnection.setRequestMethod(requestMethod);

        InputStream responseStream = null;
        final HttpResponse response;
        try {
            try {
                responseStream = urlConnection.getInputStream();
            } catch (final SocketTimeoutException socketTimeoutException) {
                throw socketTimeoutException;
            } catch (final IOException ioException) {
                responseStream = urlConnection.getErrorStream();
            }

            final int statusCode = urlConnection.getResponseCode();
            final String responseBody = responseStream == null ? "" : convertStreamToString(responseStream);

            response = new HttpResponse(statusCode, responseBody, urlConnection.getHeaderFields());
        } finally {
            safeCloseStream(responseStream);
        }

        return response;
    }

    private HttpURLConnection setupConnection() throws IOException {
        final HttpURLConnection urlConnection = HttpUrlConnectionFactory.createHttpURLConnection(mRequestUrl);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB_MR2) {
            urlConnection.setRequestProperty("Connection", "close");
        }

        // Apply request headers and update the headers with default attributes first
        final Set<Map.Entry<String, String>> headerEntries = mRequestHeaders.entrySet();
        for (final Map.Entry<String, String> entry : headerEntries) {
            urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
        }

        urlConnection.setConnectTimeout(CONNECT_TIME_OUT);
        urlConnection.setReadTimeout(READ_TIME_OUT);
        urlConnection.setInstanceFollowRedirects(true);
        urlConnection.setUseCaches(false);
        urlConnection.setDoInput(true);

        setRequestBody(urlConnection, mRequestContent, mRequestContentType);

        return urlConnection;
    }

    private static void setRequestBody(final HttpURLConnection connection, final byte[] contentRequest,
                                       final String requestContentType) throws IOException {
        if (null != contentRequest) {
            connection.setDoOutput(true);

            if (null != requestContentType && !requestContentType.isEmpty()) {
                connection.setRequestProperty("Content-Type", requestContentType);
            }

            connection.setRequestProperty("Content-Length",
                    Integer.toString(contentRequest.length));
            connection.setFixedLengthStreamingMode(contentRequest.length);

            OutputStream out = null;
            try {
                out = connection.getOutputStream();
                out.write(contentRequest);
            } finally {
                safeCloseStream(out);
            }
        }
    }

    /**
     * Convert stream into the string.
     *
     * @param inputStream {@link InputStream} to be converted to be a string.
     * @return The converted string
     * @throws IOException Thrown when failing to access inputStream stream.
     */
    private static String convertStreamToString(final InputStream inputStream) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }

            return sb.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /**
     * Close the stream safely.
     *
     * @param stream stream to be closed
     */
    private static void safeCloseStream(final Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (final IOException e) {
                // swallow error in this case
                // TODO: log the error
                // Logger.e(TAG, "Encounter IO exception when trying to close the stream", e);
            }
        }
    }

    /**
     *
     * @param statusCode The status to check.
     * @return True if the status code is 500, 503 or 504, false otherwise.
     */
    private static boolean isRetryableError(final int statusCode) {
        return statusCode == HttpURLConnection.HTTP_INTERNAL_ERROR
                || statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT
                || statusCode == HttpURLConnection.HTTP_UNAVAILABLE;
    }

    /**
     * Having the thread wait for 1 second before doing the retry to avoid hitting server immediately.
     */
    private void waitingBeforeRetry() {
        try {
            Thread.sleep(RETRY_TIME_WAITING_PERIOD);
        } catch (final InterruptedException interrupted) {
            // Swallow the exception here since we don't want to fail if error happens when having the thread hanging.
            // TODO: Logger.i(TAG, "Fail the have the thread waiting for 1 second before doing the retry")
        }
    }
}
