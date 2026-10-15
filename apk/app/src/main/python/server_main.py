import os
import threading


def start(base_dir):
    os.environ["CUPFOX_BASE"] = str(base_dir)

    def run():
        import app as cupfox
        cupfox.app.run(host="127.0.0.1", port=5000, debug=False, use_reloader=False, threaded=True)

    thread = threading.Thread(target=run, name="cupfox-flask", daemon=True)
    thread.start()
    return True
