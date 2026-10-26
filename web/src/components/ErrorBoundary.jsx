import React from 'react';

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null, errorInfo: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
    this.setState({ errorInfo });
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null, errorInfo: null });
    this.props.onReset?.();
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-4">
          <div className="bg-zinc-900 border border-rose-500/50 w-full max-w-lg rounded-3xl p-6 shadow-2xl space-y-4 text-center">
            <div className="w-16 h-16 rounded-2xl bg-rose-500/20 text-rose-400 border border-rose-500/40 text-3xl flex items-center justify-center mx-auto">
              ⚠️
            </div>
            <div>
              <h3 className="text-xl font-black text-white">Đã Xảy Ra Lỗi Hiển Thị</h3>
              <p className="text-xs text-zinc-400 mt-1">
                Giao diện đã tự động ngăn chặn sự cố làm sập toàn bộ ứng dụng.
              </p>
            </div>

            <div className="bg-zinc-950 p-3 rounded-xl border border-zinc-800 text-left overflow-auto max-h-36 font-mono text-[11px] text-rose-300">
              {this.state.error?.toString()}
            </div>

            <div className="flex gap-3 pt-2">
              <button
                type="button"
                onClick={this.handleReset}
                className="flex-1 py-2.5 rounded-xl bg-zinc-800 hover:bg-zinc-700 text-zinc-200 font-bold text-xs border border-zinc-700 transition-colors"
              >
                Đóng & Quay Lại
              </button>
              <button
                type="button"
                onClick={() => window.location.reload()}
                className="flex-1 py-2.5 rounded-xl bg-rose-600 hover:bg-rose-500 text-white font-bold text-xs shadow-lg transition-all"
              >
                Tải Lại Trang
              </button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
