'use strict';

customElements.whenDefined('deep-chat').then(() => {
  const chat = document.querySelector('#chat');

  // 既存 API に合わせ、今回の質問だけを送信する。
  chat.requestInterceptor = (request) => {
    if (request.body instanceof FormData) {
      const message = JSON.parse(request.body.get('message1') || '{}').text?.trim();
      const image = request.body.get('files');
      if (!(image instanceof File) || image.size > 5 * 1024 * 1024) {
        return { error: '画像は5MB以下にしてください。' };
      }
      request.body.delete('message1');
      request.body.set('message', message || 'この画像を説明してください。');
      return request;
    }
    const message = request.body.messages.at(-1)?.text?.trim();
    if (!message) return { error: '質問を入力してください。' };
    request.body = { message };
    return request;
  };

  chat.responseInterceptor = (response) => {
    if (typeof response.content !== 'string' || !response.content.trim()) {
      return { error: '回答を取得できませんでした。OVMS とアプリのログを確認してください。' };
    }
    return { text: response.content };
  };

  chat.connect = { url: '/api/chat', method: 'POST' };
});
